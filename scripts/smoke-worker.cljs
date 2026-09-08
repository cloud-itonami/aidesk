#!/usr/bin/env nbb
;; smoke-worker — 実際にビルドされた bundle を import して叩く。
;;
;; ここが「deploy される成果物」に触る唯一の検査である。test/aidesk/route_test.cljc は
;; ソースの判断を固定するが、bundle が本当に Worker の形で答えるかは言えない ——
;; export の形、shadow の :advanced-optimization、`shadow.resource/inline` で焼いた
;; CSS は、どれもビルドを通って初めて存在する。
;;
;; Usage:  nbb scripts/smoke-worker.cljs [<dist/worker.js>]
;; Exit:   0 全て期待どおり · 1 期待と違う · 2 判定できなかった（bundle が無い等）

(require '["node:fs" :as fs] '["node:path" :as path] '["node:url" :as url]
         '[kotoba.lang.text :as str])

(def bundle
  "ESM の import は相対パスを package 名と読むので、必ず絶対パスに直してから
  file:// URL にする（`dist/worker.js` をそのまま渡すと『Cannot find package dist』
  になる。実測）。"
  (let [a (first (remove #(str/starts-with? % "--") *command-line-args*))]
    (.resolve path (or a "dist/worker.js"))))

(def failures (atom []))
(def checks (atom 0))
(defn check! [label expected actual]
  (swap! checks inc)
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" label
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))))

(defn undetermined!
  "測れなかったときの終わり方。**0 とも 1 とも別の 2 で終わる。**

  ただし既に落ちた check があるなら exit 1 —— 『偽であると分かったこと』は
  『測れなかったこと』より強い答えで、それを 2 に丸めると、本物の失敗が
  『判定できなかった』に化ける。"
  [msg]
  (println (str "UNDETERMINED\t" msg))
  (let [f @failures]
    (if (seq f)
      (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
          (js/process.exit 1))
      (do (println "Refusing to report a pass.")
          (js/process.exit 2)))))

(when-not (.existsSync fs bundle)
  (undetermined! (str "no bundle at " bundle
                      " — build it first (docs/operator-quickstart.md §4)")))

(def sentinel
  "env の VALUE がページに出ていないことを確かめるための印。実在しそうな値だと
  他の文言と偶然一致しうるし、引用符ごと探すと renderer が \" を &quot; に escape
  するので**決して一致しない** —— つまり検査が構造的に落ちなくなる。"
  "SENTINEL-a1d3sk00-7c41e9")

(def router-url
  "中継先は **値そのもの** がページに出る。`.invalid`（RFC 2606 で必ず解決しない
  TLD）にしておくと、出ていることを実 DNS に依存せず確かめられる。到達不能の
  検査（502）でも同じ理由で使う。"
  "https://mcp.example.invalid/xrpc/probe")

(def env #js {"APP_NANOID" "a1d3sk00"
              "APP_UI_TYPE" sentinel
              "AGENTGATEWAY_MCP_ROUTER_URL" router-url})

(def real-fetch js/globalThis.fetch)
(def captured (atom []))

(defn install-stub!
  "上流を差し替えて、**BFF が実際に送るもの**を捕まえる。実 DNS に依存せずに
  中継の形（封筒・ヘッダ・多段パスの扱い）を bundle レベルで見るための唯一の手段。"
  [respond]
  (reset! captured [])
  (set! (.-fetch js/globalThis)
        (fn [u opts]
          (swap! captured conj {:url u :opts opts})
          (js/Promise.resolve (respond)))))

(defn restore-fetch! [] (set! (.-fetch js/globalThis) real-fetch))

(defn- res->map [res]
  (-> (.text res)
      (.then (fn [body] {:status (.-status res)
                         :ct (.get (.-headers res) "content-type")
                         :allow (.get (.-headers res) "allow")
                         :body body}))))

(defn- call
  ([h method p] (call h method p nil nil))
  ([h method p body headers]
   (let [init #js {:method method}]
     (when body (set! (.-body init) body))
     (when headers (set! (.-headers init) headers))
     (let [req (js/Request. (str "https://aidesk.etzhayyim.com" p) init)]
       (-> (js/Promise.resolve ((.-fetch h) req env #js {}))
           (.then res->map))))))

(defn- json-res [obj status]
  (js/Response. (js/JSON.stringify (clj->js obj))
                #js {:status status :headers #js {"content-type" "application/json"}}))

(defn- sent []
  (let [{:keys [url opts]} (last @captured)]
    (when opts
      {:url url
       :headers (js->clj (.-headers opts))
       :body (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)})))

(defn finish []
  (println (str "CHECKS\t" @checks))
  (when (< @checks 25)
    (undetermined! (str "only " @checks " checks ran — the suite did not complete")))
  (let [f @failures]
    (if (seq f)
      (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
          (js/process.exit 1))
      (do (println "OK\tthe built bundle answers as the route table says")
          (js/process.exit 0)))))

(-> (js/import (.-href (.pathToFileURL url bundle)))
    (.then
     (fn [m]
       (let [h (.-default m)]
         (check! "default export has fetch" true (fn? (.-fetch h)))
         (-> (js/Promise.all
              #js [(call h "GET" "/") (call h "GET" "/health")
                   (call h "POST" "/xrpc/") (call h "OPTIONS" "/xrpc/x")
                   (call h "GET" "/nope") (call h "POST" "/health")
                   (call h "GET" "/xrpc/x") (call h "GET" "/_app/meta")])
             (.then
              (fn [[page health bad pre nf mna wrong meta]]
                (check! "GET / status" 200 (:status page))
                (check! "GET / is html" true (str/includes? (or (:ct page) "") "text/html"))
                ;; ページは route 表から描かれる。表にある path が全部出ていること。
                (doseq [p ["/health" "/xrpc/:nsid"]]
                  (check! (str "page advertises " p) true (str/includes? (:body page) p)))
                ;; env のキーは出す、値は出さない。**印を 2 つ使う** —— 片方だけだと
                ;; 「全部隠す」実装も「全部出す」実装も通ってしまう。
                (check! "page shows a var key" true (str/includes? (:body page) "APP_NANOID"))
                (check! "page hides other var values" false (str/includes? (:body page) sentinel))
                (check! "page shows the relay target it uses" true (str/includes? (:body page) router-url))
                ;; デザインシステムの検査は **2 本**。`dads-table` が在ることだけを
                ;; 見る形は落ちない —— それは view が出す markup であって、CSS が
                ;; 1 バイトも入っていないページにも現れる。実測（このページ、
                ;; 2026-08-18）: `dads-table` は css 込み 74 / css 無し **5**、
                ;; `class="dads-table"` は **両方 1**。`--color-primitive-blue` は
                ;; 45 / **0**。前者は「view がライブラリを呼んだ」、後者は
                ;; 「stylesheet が実際に bundle へ入った」——別の主張である。
                (check! "page uses the design system components" true
                        (str/includes? (:body page) "class=\"dads-table\""))
                (check! "page carries the stylesheet itself" true
                        (str/includes? (:body page) "--color-primitive-blue"))
                (check! "GET /health status" 200 (:status health))
                (check! "health names its routes" true (str/includes? (:body health) "/xrpc/:nsid"))
                (check! "health names the nanoid it was given" true
                        (str/includes? (:body health) "a1d3sk00"))
                ;; nsid 無しの XRPC は 400。移行前と同じ文言。
                (check! "POST /xrpc/ status" 400 (:status bad))
                (check! "POST /xrpc/ says what is missing" true
                        (str/includes? (:body bad) "Missing XRPC method"))
                (check! "OPTIONS preflight" 204 (:status pre))
                (check! "unknown path" 404 (:status nf))
                (check! "wrong method on /health" 405 (:status mna))
                (check! "wrong method on /xrpc" 405 (:status wrong))
                (check! "405 names the methods it allows" "POST, OPTIONS" (:allow wrong))
                ;; /_app/meta は src/app.ts にあったが deploy されていなかった経路。
                ;; 移行では持ち越していないので 404 である（ADR-0001）。
                (check! "/_app/meta is not carried over" 404 (:status meta))))
             (.then
              (fn [_]
                ;; ここから上流を差し替える。**実 DNS に依存せずに**、中継の封筒・
                ;; ヘッダ・多段パスの扱いを bundle レベルで見る。
                (install-stub! #(json-res {:result {:structuredContent {:jobs [] :stub true}}} 200))
                (-> (call h "POST" "/xrpc/a/b" "{\"limit\":5}"
                          #js {"authorization" "Bearer smoke-token"
                               "x-request-id" "smoke-1"
                               "content-type" "application/json"})
                    (.then
                     (fn [multi]
                       ;; **状態の検査を捕捉の手前に置く。** 逆にすると、多段パスを
                       ;; 400 で弾く実装（= 移行ではなく方針変更）が『測れなかった』
                       ;; として exit 2 になり、偽であると分かっているのに
                       ;; そう言えなくなる。実測 2026-08-18、この順序ミスを
                       ;; mutation が炙り出した。
                       (check! "multi-segment nsid is relayed, not rejected" 200 (:status multi))
                       (if-let [s (sent)]
                         (do
                           (check! "relay posts to the configured router" router-url (:url s))
                           (check! "relay names the multi-segment nsid verbatim" "a/b"
                                   (get-in s [:body :params :name]))
                           (check! "relay wraps the body in tools/call" "tools/call"
                                   (get-in s [:body :method]))
                           (check! "relay passes the posted body as arguments" {:limit 5}
                                   (get-in s [:body :params :arguments]))
                           (check! "relay forwards authorization upstream" "Bearer smoke-token"
                                   (get-in s [:headers "authorization"]))
                           (check! "relay names itself" "cljs-edge-bff"
                                   (get-in s [:headers "x-etzhayyim-bff"]))
                           (check! "relay drops the inherited content-length" nil
                                   (get-in s [:headers "content-length"]))
                           (check! "relay unwraps result.structuredContent" true
                                   (str/includes? (:body multi) "\"stub\":true")))
                         (undetermined!
                          (str "the bundle did not call the stubbed fetch — relay behaviour "
                               "could not be measured (status was " (:status multi) ")")))))))) 
             (.then
              (fn [_]
                (install-stub! #(json-res {:result {:structuredContent {:ok 1}}} 200))
                (-> (call h "POST" "/xrpc/com.etzhayyim.apps.aidesk.listDesignJobs" "{}" nil)
                    (.then (fn [_]
                             (check! "single-segment nsid is treated the same way"
                                     "com.etzhayyim.apps.aidesk.listDesignJobs"
                                     (get-in (sent) [:body :params :name])))))))
             (.then
              (fn [_]
                (install-stub! #(json-res {:error {:message "stub: upstream refused"}} 200))
                (-> (call h "POST" "/xrpc/com.x.y" "{}" nil)
                    (.then (fn [r]
                             (check! "an upstream error becomes 502" 502 (:status r))
                             (check! "the upstream error text survives" true
                                     (str/includes? (:body r) "stub: upstream refused")))))))
             (.then
              (fn [_]
                (install-stub! #(json-res {:error {:message "boom"}} 503))
                (-> (call h "POST" "/xrpc/com.x.y" "{}" nil)
                    (.then (fn [r]
                             (check! "a non-2xx upstream keeps its status" 503 (:status r)))))))
             (.then
              (fn [_]
                ;; 実 fetch に戻して、到達できない上流が 200 に潰れないことを見る。
                ;; .invalid は必ず解決しないので、この検査は実 DNS の状態に依存しない。
                (restore-fetch!)
                (-> (call h "POST" "/xrpc/com.x.y" "{}" nil)
                    (.then (fn [r]
                             (check! "an unreachable upstream is 502, not 200" 502 (:status r))
                             (check! "the 502 names the url it tried" true
                                     (str/includes? (:body r) router-url)))))))
             (.then (fn [_] (finish)))
             (.catch (fn [e]
                       (restore-fetch!)
                       (undetermined! (str "could not exercise the bundle: " (.-message e)))))))))
    (.catch (fn [e]
              (undetermined! (str "could not import the bundle: " (.-message e))))))
