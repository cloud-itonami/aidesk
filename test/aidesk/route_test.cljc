(ns aidesk.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [aidesk.route :as route]
            [aidesk.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))
  (is (= :not-found (:action (route/dispatch "GET" "/nope")))))

(deftest dispatch-xrpc
  (testing "単一セグメントの nsid"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.aidesk.listDesignJobs"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.aidesk.listDesignJobs"))))
  (testing "空の nsid だけが 400。多段は移行前と同じく転送する（絞るのは方針変更）"
    (is (= {:action :bad-request :reason "Missing XRPC method"}
           (route/dispatch "POST" "/xrpc/")))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "移行前の BFF に NSID prefix フィルタは無い（src/app.ts のそれは deploy されていない）"
    (is (= {:action :xrpc :nsid "com.example.totally.unrelated.method"}
           (route/dispatch "POST" "/xrpc/com.example.totally.unrelated.method"))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= {:action :method-not-allowed :allow "POST, OPTIONS"}
           (route/dispatch "GET" "/xrpc/x")))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x"
         (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う（移行前と同じ）"
    (is (= "https://b.example"
           (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                  :MCP_ROUTER_URL "https://b.example"})))))

(deftest relay-headers-forwards-what-the-migrated-route-forwarded
  (let [out (route/relay-headers {"host" "aidesk.etzhayyim.com"
                                  "content-length" "17"
                                  "content-type" "text/plain"
                                  "authorization" "Bearer t0ken"
                                  "x-request-id" "r-1"}
                                 "com.x.y")]
    (testing "host と content-length は落とす（body を組み直すので長さは嘘になる）"
      (is (nil? (get out "host")))
      (is (nil? (get out "content-length"))))
    (testing "**それ以外は転送する** —— 3 本だけ送る実装にすると認証が黙って消える"
      (is (= "Bearer t0ken" (get out "authorization")))
      (is (= "r-1" (get out "x-request-id"))))
    (testing "上書きする 3 本"
      (is (= "application/json" (get out "content-type")))
      (is (= "cljs-edge-bff" (get out "x-etzhayyim-bff")))
      (is (= "com.x.y" (get out "x-etzhayyim-xrpc-method"))))))

(deftest envelope-shape
  (is (= {:jsonrpc "2.0" :id "id-1" :method "tools/call"
          :params {:name "com.x.y" :arguments {:limit 5}}}
         (route/envelope "com.x.y" "id-1" {:limit 5})))
  (testing "body 無しは arguments {}（移行前も同じ）"
    (is (= {} (get-in (route/envelope "com.x.y" "id-1" nil) [:params :arguments])))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}}))))
  (is (= "boom" (:error (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表から描く。固定値を焼かない（移行前の欠陥）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "https://mcp.example/x"))
      (testing "移行前のページが出していた足場の文言が戻っていない"
        (is (not (str/includes? html "No public route is declared")))
        (is (not (str/includes? html "No public vars are declared")))))))
