(ns hc.client.sandbox.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.sandbox.model :as model]))

(deftest origin-normalization
  (testing "absolute URLs keep scheme, host and non-default port"
    (is (= "https://example.com" (model/normalize-origin "https://EXAMPLE.com/a?b=1")))
    (is (= "http://127.0.0.1:8080" (model/normalize-origin "http://127.0.0.1:8080/x")))
    (is (= "https://example.com" (model/normalize-origin "https://example.com:443/"))))
  (testing "default ports and non-http schemes are not origins"
    (is (= "http://example.com" (model/normalize-origin "http://example.com:80/")))
    (is (nil? (model/normalize-origin "file:///tmp/x")))
    (is (nil? (model/normalize-origin "data:text/html,x"))))
  (testing "http-url? is the only scheme allowed to open externally"
    (is (model/http-url? "https://example.com"))
    (is (model/http-url? "http://localhost:3000"))
    (is (not (model/http-url? "javascript:alert(1)")))
    (is (not (model/http-url? "mailto:a@b.c")))))

(deftest external-origin-scan
  (let [html (str "<script src=\"https://cdn.example.com/x.js\"></script>"
                  "<img src=\"//img.example.com/a.png\">"
                  "<a href=\"http://localhost:9000/x\">x</a>"
                  "<a href=\"relative/path\">r</a>"
                  "<div style=\"background:url(https://cdn.example.com/a.png)\"></div>")]
    (is (= ["http://localhost:9000"
            "https://cdn.example.com"
            "https://img.example.com"]
           (model/external-origins html))))
  (is (= [] (model/external-origins "<p>no urls</p>"))))

(deftest content-security-policy
  (let [closed (model/content-security-policy [])
        open (model/content-security-policy ["https://cdn.example.com"])]
    (testing "closed by default"
      (is (re-find #"default-src 'none'" closed))
      (is (re-find #"connect-src 'none'" closed))
      (is (re-find #"frame-src 'none'" closed))
      (is (re-find #"form-action 'none'" closed))
      (is (not (re-find #"https://" closed))))
    (testing "only named origins are added"
      (is (re-find #"connect-src https://cdn\.example\.com" open))
      (is (re-find #"script-src 'unsafe-inline' https://cdn\.example\.com" open))
      (is (re-find #"form-action 'none'" open))
      (is (not (re-find #"https://other\.example" open))))))

(deftest document-injection
  (let [html "<html><head><title>x</title></head><body><script>window.x=1</script></body></html>"
        injected (model/document-html {:html html
                                       :token "t-1"
                                       :mode :app
                                       :config {:app {:id "a1"}}
                                       :allowed-origins ["https://cdn.example.com"]})
        fragment (model/document-html {:html "<p>hello</p>"
                                        :token "t-2"
                                        :mode :preview
                                        :config {}
                                        :allowed-origins []})]
    (testing "policy and bridge come before application scripts"
      (is (< (.indexOf injected "Content-Security-Policy")
             (.indexOf injected "window.x=1")))
      (is (< (.indexOf injected "data-hc-sandbox")
             (.indexOf injected "window.x=1")))
      (is (re-find #"https://cdn\.example\.com" injected)))
    (testing "fragments are wrapped"
      (is (re-find #"^<!DOCTYPE html><html><head>" fragment))
      (is (re-find #"<body><p>hello</p></body>" fragment)))
    (testing "injection is idempotent"
      (is (= injected (model/document-html {:html injected
                                            :token "t-1"
                                            :mode :app
                                            :config {}
                                            :allowed-origins []}))))))

(deftest bridge-escapes-script-close
  (let [html (model/document-html
              {:html "<html><head></head><body></body></html>"
               :token "t"
               :mode :preview
               :config {:name "</script><script>alert(1)</script>"}
               :allowed-origins []})]
    (is (not (re-find #"</script><script>alert" html)))
    (is (re-find #"\\u003c/script" html))))

(deftest bridge-preserves-dollar-config
  (let [html (model/document-html
              {:html "<html><head></head><body></body></html>"
               :token "t"
               :mode :preview
               :config {:name "$&$1"}
               :allowed-origins []})]
    (is (re-find #"\$&\$1" html))))

(deftest message-gate
  (testing "a frame message needs the exact token and a known type"
    (is (model/valid-message? "t" {:token "t" :type "hc/ready"}))
    (is (model/valid-message? "t" {:token "t" :type "hc/storage-set"}))
    (is (not (model/valid-message? "t" {:token "other" :type "hc/ready"})))
    (is (not (model/valid-message? "t" {:token "t" :type "hc/evil"})))
    (is (not (model/valid-message? nil {:token nil :type "hc/ready"}))))
  (testing "request and event classification"
    (is (model/request-message? {:token "t" :type "hc/storage-get"}))
    (is (model/event-message? {:token "t" :type "hc/external-link"}))
    (is (= :external-submit
           (model/message-kind {:token "t" :type "hc/external-submit"})))))


(deftest user-content-cannot-skip-injection
  (let [html "<html><head></head><body><div data-hc-sandbox=\"1\">not injected</div></body></html>"
        injected (model/document-html {:html html :token "token-1" :mode :app
                                       :config {} :allowed-origins []})]
    (is (re-find #"Content-Security-Policy" injected))
    (is (re-find #"data-hc-sandbox-token=\"token-1\"" injected))
    (is (< (.indexOf injected "Content-Security-Policy")
           (.indexOf injected "not injected")))))

(deftest fake-head-in-comment-cannot-capture-injection
  (let [html (str "<html><!-- <head> --><head><title>x</title></head>"
                  "<body><script>window.x=1</script></body></html>")
        injected (model/document-html {:html html :token "token-2" :mode :preview
                                       :config {} :allowed-origins []})]
    (is (re-find #"Content-Security-Policy" injected))
    (is (< (.indexOf injected "Content-Security-Policy")
           (.indexOf injected "window.x=1")))
    (is (not (re-find #"<!-- <head> <meta http-equiv" injected)))))

(deftest form-submit-payload-hides-values
  (let [bridge (model/bridge-js "token-3" :preview {})]
    (is (re-find #"valueLength" bridge))
    (is (not (re-find #"value: typeof value === 'string'" bridge)))))
