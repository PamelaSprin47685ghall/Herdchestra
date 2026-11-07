// Real-browser consumer regression for the conversation streaming selection.
//
// The focused shadow-cljs build (`:conversation-selection-test`) mounts only
// `hc.client.conversation.view` with scripted stream chunks.  This runner
// serves that build, drives a real Chromium page, selects a streamed
// paragraph, and proves that:
//   1. ordinary stream growth keeps the live DOM Range;
//   2. a Blink character-data reset is repaired after the commit;
//   3. a deliberate composer click is never undone by a later chunk;
//   4. the composer draft and the reader's scroll position survive.
//
// Run after `npx shadow-cljs compile :conversation-selection-test`.
// Playwright is an external test dependency (the same one used by
// verify_browser_ui.cjs).  Set PLAYWRIGHT_PATH when it is not resolvable from
// the repository.
const http = require("http");
const fs = require("fs");
const path = require("path");

let chromium;
try {
  ({ chromium } = require(process.env.PLAYWRIGHT_PATH || "playwright"));
} catch (error) {
  console.error(
    "Playwright is required for the real-browser selection test; set PLAYWRIGHT_PATH " +
      "to the module directory or install playwright."
  );
  console.error(String(error));
  process.exit(2);
}

const OUT = path.resolve(__dirname, "../../target/conversation-selection-test");
const ENTRY = path.join(OUT, "js/main.js");
if (!fs.existsSync(ENTRY)) {
  console.error(
    `missing ${ENTRY}; run "npx shadow-cljs compile :conversation-selection-test" first`
  );
  process.exit(2);
}

const INITIAL = [
  "# E2E03 长回复",
  "",
  "第一段：这是流式长回复的第一段，包含可选择的文字。滚动到上方后，新增内容不应抢走位置。",
  "",
  "第二段：选区应保持稳定。这里是更多可读文本，用来制造足够的高度。",
  "",
  "第三段：为了覆盖长文本，继续加入若干句子。读者在向上滚动时，客户端不应为了“稳定”而停止接收流。",
  "",
  "第四段：继续追加内容以拉长流。Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega.",
  "",
  "第五段：最后一段。停止按钮应保留已生成内容，并让发送按钮回到可用状态。",
  "",
  "第六段：更多行。".repeat(20),
].join("\n");

const CHUNK_ONE = "\n\n流式追加一：新的文字继续到达，旧选区必须保留。".repeat(3);
const CHUNK_TWO = "\n\n流式追加二：浏览器重置 Range 后仍应恢复。".repeat(3);
const CHUNK_THREE = "\n\n流式追加三：操作者主动点击输入区后不得复活旧选区。".repeat(3);

const html = `<!doctype html><html><head><meta charset="utf-8">
<style>html,body,#app{height:100%;margin:0}</style>
</head><body><div id="app"></div><script src="/js/main.js"></script></body></html>`;

const server = http.createServer((request, response) => {
  const url = decodeURIComponent((request.url || "/").split("?")[0]);
  if (url === "/" || url === "/index.html") {
    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(html);
    return;
  }
  const file = path.resolve(OUT, `.${url}`);
  if (!file.startsWith(OUT + path.sep)) {
    response.writeHead(403);
    response.end();
    return;
  }
  fs.readFile(file, (error, data) => {
    if (error) {
      response.writeHead(404);
      response.end();
      return;
    }
    response.writeHead(200, {
      "content-type": file.endsWith(".js")
        ? "text/javascript; charset=utf-8"
        : "application/octet-stream",
    });
    response.end(data);
  });
});

function fail(message) {
  throw new Error(message);
}

(async () => {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  let browser = null;
  try {
    browser = await chromium.launch({
      executablePath: process.env.HC_CHROME || "/usr/bin/google-chrome",
      headless: true,
      args: ["--no-sandbox", "--disable-gpu"],
    });
    const page = await browser.newPage({ viewport: { width: 1440, height: 700 } });
    page.setDefaultTimeout(20000);
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(String(error)));

    await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: "load" });
    await page.waitForFunction(() => window.hcSelectionTest, null, {
      timeout: 20000,
    });
    await page.evaluate((text) => window.hcSelectionTest.mount(text), INITIAL);
    await page.waitForSelector(".hc-text");

    const before = await page.evaluate(() => {
      const root = document.querySelector(".hc-conversation-messages");
      if (!root) throw new Error("conversation scroller missing");
      root.scrollTop = 0;
      return {
        length: root.innerText.length,
        scrollHeight: root.scrollHeight,
        clientHeight: root.clientHeight,
      };
    });
    if (before.scrollHeight <= before.clientHeight) {
      fail("conversation did not overflow; scroll assertion would be empty");
    }

    const selected = await page.evaluate(() => {
      const root = document.querySelector(".hc-conversation-messages");
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      let node = null;
      while (walker.nextNode()) {
        if (walker.currentNode.textContent.includes("第一段")) {
          node = walker.currentNode;
          break;
        }
      }
      if (!node) throw new Error("no streamed paragraph containing 第一段");
      const range = document.createRange();
      range.selectNodeContents(node);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      // The surface captures on the same mouseup gesture a reader uses.
      root.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
      return selection.toString();
    });
    if (!selected.includes("第一段")) {
      fail(`selection did not take: ${JSON.stringify(selected.slice(0, 60))}`);
    }

    // 1. Ordinary growth while the selection is live.
    await page.evaluate((delta) => window.hcSelectionTest.appendText(delta), CHUNK_ONE);
    await page.waitForFunction(
      (length) =>
        document.querySelector(".hc-conversation-messages").innerText.length > length,
      before.length
    );
    const grown = await page.evaluate(() => ({
      selection: window.getSelection().toString(),
      text: document.querySelector(".hc-conversation-messages").innerText,
      scrollTop: document.querySelector(".hc-conversation-messages").scrollTop,
      draft: document.querySelector("textarea[aria-label='消息输入']").value,
    }));
    if (!grown.selection.includes("第一段")) {
      fail("stream growth cleared the live selection");
    }
    if (!grown.text.includes("流式追加一")) {
      fail("stream stopped instead of appending text");
    }
    if (grown.scrollTop > 80) {
      fail(`stream stole the reader's scroll position: ${grown.scrollTop}`);
    }
    if (grown.draft !== "E2E03 下一条草稿") {
      fail(`stream rewrote the composer draft: ${JSON.stringify(grown.draft)}`);
    }

    // 2. Blink resets the offsets when React replaces the streamed text node.
    //    The surface must restore the same range after the commit.
    await page.evaluate(() => window.hcSelectionTest.collapseSelection());
    const beforeRestore = grown.text.length;
    await page.evaluate((delta) => window.hcSelectionTest.appendText(delta), CHUNK_TWO);
    await page.waitForFunction(
      (length) =>
        document.querySelector(".hc-conversation-messages").innerText.length > length,
      beforeRestore
    );
    const restored = await page.evaluate(() => window.getSelection().toString());
    if (!restored.includes("第一段")) {
      fail("selection was not restored after the browser character-data reset");
    }
    if (restored !== selected) {
      fail("the restored range changed the reader's selected text");
    }

    // 3. A deliberate composer click clears the tracked range.  A later chunk
    //    must not resurrect it.
    await page.locator("textarea[aria-label='消息输入']").click();
    await page.evaluate(() => window.hcSelectionTest.collapseSelection());
    const beforeClear = await page.evaluate(
      () => document.querySelector(".hc-conversation-messages").innerText.length
    );
    await page.evaluate((delta) => window.hcSelectionTest.appendText(delta), CHUNK_THREE);
    await page.waitForFunction(
      (length) =>
        document.querySelector(".hc-conversation-messages").innerText.length > length,
      beforeClear
    );
    const cleared = await page.evaluate(() => window.getSelection().toString());
    if (cleared !== "") {
      fail("a deliberate composer click was undone by the next stream chunk");
    }
    if (pageErrors.length) {
      fail(`page errors: ${pageErrors.join(" | ")}`);
    }
    console.log(
      JSON.stringify({
        ok: true,
        selected: selected.slice(0, 40),
        afterRestore: restored.slice(0, 40),
        scrollTop: grown.scrollTop,
        draft: grown.draft,
      })
    );
    console.log("SMOKE OK");
  } finally {
    if (browser) await browser.close();
    server.close();
  }
})().catch((error) => {
  console.error("SMOKE FAILED", error);
  process.exitCode = 1;
});
