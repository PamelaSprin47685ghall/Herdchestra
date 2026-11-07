(ns hc.server.extensions.support
  "Test helpers for extension installation.

  Every helper uses a temporary directory or an in-process loopback server.
  No test starts a user's real MCP server or reads a user path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.test-jvm :as test-jvm])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file FileVisitOption Files LinkOption Path)
           (java.util.zip ZipEntry ZipOutputStream)
           (javax.tools ToolProvider)))

(defn temp-dir
  []
  (str (Files/createTempDirectory "hc-extensions-test-"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree
  [root]
  (let [path (Path/of (str root) (make-array String 0))]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array FileVisitOption 0))]
        (doseq [^Path entry (->> (iterator-seq (.iterator stream))
                                 (sort-by #(.getNameCount ^Path %))
                                 reverse)]
          (try
            (Files/deleteIfExists entry)
            (catch Exception _ nil)))))))

(defmacro with-temp-dir
  [[binding] & body]
  `(let [~binding (temp-dir)]
     (try
       ~@body
       (finally
         (delete-tree ~binding)))))

(defn zip-bytes
  [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path content] entries]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (if (bytes? content)
                      content
                      (.getBytes (str content) StandardCharsets/UTF_8)))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn java-exe
  []
  (let [name (if (str/starts-with? (System/getProperty "os.name") "Windows")
               "java.exe"
               "java")
        path (io/file (System/getProperty "java.home") "bin" name)]
    (str path)))

(def stub-source
  "import java.io.*;
import java.nio.file.*;
public class McpStub {
  public static void main(String[] args) throws Exception {
    boolean hang = false;
    boolean slowCall = false;
    boolean marker = false;
    String markerPath = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(\"--hang\")) hang = true;
      if (args[i].equals(\"--slow-call\")) slowCall = true;
      if (args[i].equals(\"--marker\") && i + 1 < args.length) {
        marker = true;
        markerPath = args[i + 1];
      }
    }
    if (hang) { Thread.sleep(60000); return; }
    BufferedReader r = new BufferedReader(new InputStreamReader(System.in, \"UTF-8\"));
    PrintWriter w = new PrintWriter(new OutputStreamWriter(System.out, \"UTF-8\"), true);
    String line;
    while ((line = r.readLine()) != null) {
      String method = method(line);
      if (\"initialize\".equals(method)) {
        w.println(\"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\" + id(line) + \",\\\"result\\\":{\\\"protocolVersion\\\":\\\"2024-11-05\\\",\\\"capabilities\\\":{\\\"tools\\\":{}},\\\"serverInfo\\\":{\\\"name\\\":\\\"stub\\\",\\\"version\\\":\\\"1.0\\\"}}}\");
      } else if (\"tools/list\".equals(method)) {
        w.println(\"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\" + id(line) + \",\\\"result\\\":{\\\"tools\\\":[{\\\"name\\\":\\\"echo\\\",\\\"description\\\":\\\"Echo text\\\",\\\"inputSchema\\\":{\\\"type\\\":\\\"object\\\"},\\\"annotations\\\":{\\\"readOnlyHint\\\":true}},{\\\"name\\\":\\\"write_file\\\",\\\"description\\\":\\\"Write a file\\\",\\\"inputSchema\\\":{\\\"type\\\":\\\"object\\\"},\\\"annotations\\\":{\\\"readOnlyHint\\\":false,\\\"destructiveHint\\\":true}},{\\\"name\\\":\\\"fail_tool\\\",\\\"description\\\":\\\"Always fails\\\",\\\"inputSchema\\\":{\\\"type\\\":\\\"object\\\"},\\\"annotations\\\":{\\\"readOnlyHint\\\":true}}]}}\");
      } else if (\"tools/call\".equals(method)) {
        if (marker) Files.writeString(Paths.get(markerPath), \"called\");
        if (slowCall) Thread.sleep(10000);
        if (line.contains(\"\\\"name\\\":\\\"fail_tool\\\"\")) {
          w.println(\"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\" + id(line) + \",\\\"result\\\":{\\\"isError\\\":true,\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"tool failed\\\"}]}}\");
        } else {
          w.println(\"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\" + id(line) + \",\\\"result\\\":{\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"ok\\\"}]}}\");
        }
      }
    }
  }
  static String method(String line) {
    int i = line.indexOf(\"\\\"method\\\":\");
    if (i < 0) return \"\";
    int j = line.indexOf('\"', i + 9);
    int k = line.indexOf('\"', j + 1);
    return line.substring(j + 1, k);
  }
  static String id(String line) {
    int i = line.indexOf(\"\\\"id\\\":\");
    if (i < 0) return \"null\";
    int j = i + 5;
    while (j < line.length() && line.charAt(j) == ' ') j++;
    int k = j;
    while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
    return line.substring(j, k);
  }
}
")

(defn compile-stub!
  "Compile the Java stub into `dir`.  Returns the class directory."
  [dir]
  (let [source (io/file dir "McpStub.java")
        compiler (ToolProvider/getSystemJavaCompiler)]
    (when-not compiler
      (throw (ex-info "tests need a JDK with the system Java compiler"
                      {:dir dir})))
    (spit source stub-source)
    (let [errors (ByteArrayOutputStream.)
          result (.run compiler nil nil errors
                       (into-array String ["-encoding" "UTF-8"
                                           "-d" dir
                                           (str source)]))]
      (when-not (zero? result)
        (throw (ex-info "could not compile MCP stub"
                        {:output (str errors)})))
      dir)))

(defn stub-command
  [class-dir & args]
  (into [(java-exe) "-Dfile.encoding=UTF-8"]
        (concat test-jvm/child-jvm-opts
                ["-cp" class-dir "McpStub"]
                args)))
