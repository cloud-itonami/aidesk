(ns aidesk.route
  "どのハンドラが答えるかを、データと純関数で決める層。

  `.cljc` にしてあるのは意図。エッジ worker で検査する価値があるのは経路の
  判断であり、ここならブラウザもビルドもネットワークも無しに固定できる。
  Request/Response に触るのは `aidesk.worker` だけで、そこはこのファイルが
  既に決めたこと以外を決めない。

  ingress capability が qualify した時（`:native-aot` / `:wasm-aot` は今日とも
  pending —— ADR-2606290000）に最初に `.kotoba` へ移るのもここである。
  route 表はスカラと文字列の上の判断で、まさにその移行が通る形をしている。"
  (:require [clojure.string :as str]))

(def routes
  "公開している面を、データとして 1 箇所に持つ。**ページはこの表を描く。**

  移行前のページは `routeCount` と `routes` と `vars` を `+page.svelte` の中に
  literal で持っていた。2026-08-18 の commit c87bd2c が superproject の生成器で
  wrangler から書き直したが、生成器は別 repo にあり、ページは生成した瞬間の値を
  焼いたままである。ここでは worker が実際に持つ表をそのまま渡すので、
  『表示しているもの』と『実際に答えるもの』が同じ 1 つの値から出る。"
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"     :route/method :get  :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil。**

  多段パス（`/xrpc/a/b`）もそのまま通す。移行前に deploy されていた SvelteKit の
  route は rest parameter `[...path]` で受けており、`a/b` を tool 名としてそのまま
  転送していた（実測: `docs/operator-quickstart.md` 手順 5.1、NSID prefix の検査すら
  無い）。ここで 1 セグメントに絞ると失敗の起きる場所と応答が変わる ——
  **それは移行ではなく方針変更**なので、移行の commit に混ぜない。

  同じ判断が cloud-itonami/app-lo と app-ongakuka で先に行われており、揃えた。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  `:action` は `:page` / `:health` / `:xrpc` / `:cors-preflight` /
  `:bad-request` / `:method-not-allowed` / `:not-found` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          ;; 移行前の SvelteKit route と同じ文言。空の nsid だけが 400。
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  移行前の `+server.ts` と同じ解決順（`AGENTGATEWAY_MCP_ROUTER_URL` →
  `MCP_ROUTER_URL` → 既定値）で、空白だけの値は未設定として扱うところまで同じ。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(def bff-header-value
  "上流に名乗る BFF 名。移行前は `sveltekit-edge-bff` だった。

  **これは上流から観測できる値なので、黙って変えたことにしない。** Svelte が
  1 バイトも残っていない repo が sveltekit を名乗り続ける方が嘘なので変えた。
  上流（`mcp.etzhayyim.com`）は移行時点で NXDOMAIN なので、この変更を観測して
  いる者は今日は居ない。ADR-0001 に記録した。"
  "cljs-edge-bff")

(defn relay-headers
  "受け取ったヘッダ map + nsid → 上流へ送るヘッダ map。

  移行前の `+server.ts` は `new Headers(event.request.headers)` を作り、`host` を
  削って content-type と 2 本の `x-etzhayyim-*` を上書きしていた。つまり
  **`authorization` を含む受信ヘッダは上流へ転送される。** ここを『3 本だけ送る』に
  縮めると、認証つきの呼び出しが黙って認証を失う —— 移行が最もやってはいけない類の
  変更なので、転送を保つ。

  `host` に加えて `content-length` も落とす: body を組み直して送るので、受信時の
  長さは必ず嘘になる（SvelteKit では framework が組み直していた）。"
  [incoming nsid]
  (let [drop? #{"host" "content-length"}]
    (-> (into {} (remove (fn [[k _]] (drop? (str/lower-case (name k))))) incoming)
        (assoc "content-type" "application/json"
               "x-etzhayyim-bff" bff-header-value
               "x-etzhayyim-xrpc-method" nsid))))

(defn envelope
  "nsid + 入力 → MCP router へ POST する JSON-RPC 2.0 の封筒。

  移行前と同じ形（`tools/call` に包み、body を `arguments` に入れる）。`id` は
  呼び出し側が渡す（`crypto.randomUUID()` は effect なので、この層では作らない）。"
  [nsid id input]
  {:jsonrpc "2.0"
   :id id
   :method "tools/call"
   :params {:name nsid :arguments (or input {})}})

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false
     :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
