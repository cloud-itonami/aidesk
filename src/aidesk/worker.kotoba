(ns aidesk.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは `aidesk.route/dispatch` が
  決め、ページの中身は `aidesk.view` が組み、上流へ送る封筒とヘッダは
  `aidesk.route/envelope` と `aidesk.route/relay-headers` が決める。どれも `.cljc`
  なので、ブラウザもビルドも無しにテストできる。

  wrangler.jsonc の `main` は `dist/worker.js` を指し、それはこの名前空間を
  コンパイルしたものである。移行前は SvelteKit のビルド出力（tree に無い）を
  指しており、読み手が開く TypeScript はどの bundle にも入っていなかった。

  env のキーは `aget` で引く（`:advanced-optimization` 下で潰れないため。先例
  `listingops.edge.worker` と同じ約束）。"
  (:require [aidesk.route :as route]
            [aidesk.view :as view]
            [shadow.resource :as rc]
            [kotoba.lang.text :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system の
  方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json [body status]
  (->response (js/JSON.stringify (clj->js body))
              {:status status :content-type "application/json; charset=utf-8"}))

(defn- env->map
  "env の **キーだけ** を keyword で拾う（値はページに出さない）。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- headers->map [req]
  (js->clj (js/Object.fromEntries (.entries (.-headers req)))))

(defn- cors-headers []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})

(defn- parse-json
  "移行前の `+server.ts` は `request.json().catch(() => ({}))` で、**壊れた JSON を
  握り潰して `{}` として転送**していた（実測: quickstart 手順 5.1 で 400 ではなく
  200）。検証を上流に寄せる設計はありうるので、移行では挙動を変えない。"
  [text]
  (if (seq text)
    (try (js/JSON.parse text) (catch :default _ #js {}))
    #js {}))

(defn- proxy-xrpc
  "XRPC を MCP router へ中継する。移行前に deploy されていた SvelteKit の route と
  同じ形（受信ヘッダを転送し、jsonrpc の封筒に包み、result/structuredContent を
  剥がす）。"
  [req env nsid]
  (let [url (route/mcp-router-url (env->map env))
        out-headers (clj->js (route/relay-headers (headers->map req) nsid))]
    (-> (.text req)
        (.then
         (fn [text]
           (let [input (js->clj (parse-json text) :keywordize-keys true)
                 body (route/envelope nsid (.randomUUID js/crypto) input)]
             (js/fetch url #js {:method "POST"
                                :headers out-headers
                                :body (js/JSON.stringify (clj->js body))}))))
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (let [payload (try (when (seq text) (js/JSON.parse text))
                                                 (catch :default _ text))
                                    clj-payload (js->clj payload :keywordize-keys true)]
                                (if-not (.-ok resp)
                                  (json {:error "MCP router request failed"
                                         :upstream clj-payload}
                                        (.-status resp))
                                  (let [{:keys [ok? value error upstream]} (route/unwrap-mcp clj-payload)]
                                    (if ok?
                                      (json (or value {}) 200)
                                      (json {:error error :upstream upstream} 502))))))))))
        (.catch (fn [e]
                  ;; 到達できなかったことを 200 で隠さない。移行時点で
                  ;; mcp.etzhayyim.com は A レコードを返さないので、これは想像上の
                  ;; 経路ではなく今日の既定の結末である。
                  (json {:error "MCP router unreachable"
                         :detail (str (.-message e))
                         :url url}
                        502))))))

(defn- page-response [env]
  (let [e (env->map env)]
    (->response
     (view/render {:css dds-css
                   :routes route/routes
                   :vars (sort (keys e))
                   :mcp-url (route/mcp-router-url e)})
     {:status 200
      :content-type "text/html; charset=utf-8"
      :cache "public, max-age=60"})))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      ;; deploy された面が答えていることを外から確かめるための 1 本。移行前の
      ;; src/app.ts も /health を持っていたが、その worker は起動しなかった
      ;; （wrangler の main は SvelteKit を指し、実測で 404）。app.ts が返していた
      ;; model / licenseTier / businessLogic のパスは**他 repo についての主張**で、
      ;; この worker には確かめる手段が無いので持ち越していない（ADR-0001）。
      :health (json {:ok true
                     :app "aidesk"
                     :runtime "cljs"
                     :nanoid (or (aget (or env #js {}) "APP_NANOID") "a1d3sk00")
                     :routes (mapv :route/path route/routes)}
                    200)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204 :content-type "text/plain"
                                       :extra (cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (->response (js/JSON.stringify #js {:error "Method Not Allowed"})
                                      {:status 405
                                       :content-type "application/json; charset=utf-8"
                                       :extra {"allow" allow}})
      (json {:error "Not Found"
             :routes (mapv (fn [r] (str (str/upper (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
