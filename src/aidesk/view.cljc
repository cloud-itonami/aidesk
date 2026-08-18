(ns aidesk.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`（デジタル庁デザインシステム）—— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。** これは装飾の都合では
  なく、この repo が持っていた欠陥そのものへの答えである —— 移行前の
  `+page.svelte` は route 数・route 一覧・var 一覧を literal で持っており、
  最初は `routeCount: 0` と『No public route is declared next to this app
  surface』を、route を 2 本宣言している wrangler.jsonc の隣で表示していた。
  2026-08-18 の c87bd2c が superproject の生成器で書き直したが、**生成器は別 repo
  にあり、ページは生成した瞬間の値を焼いたまま**である。ここでは worker が持つ
  route 表と env をそのまま渡すので、両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う（bridge が DADS の上に再定義する）。
  DADS を base にした app の下に `shitsuke.hig` は居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれているものの中だけ。"
  (str/join
   "\n"
   [".ad-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".ad-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".ad-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "ad-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes   aidesk.route/routes（この Worker が実際に答えるもの）
   :vars     wrangler が渡した env のキー（**キー名だけ**。値は出さない）
   :mcp-url  XRPC の中継先（route/mcp-router-url の戻り値。**値そのものを出す**）"
  [{:keys [routes vars mcp-url]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "aidesk — AI Design Desk")
    [:p {:class "ad-lede"}
     "画像・テキストから CAD（CadQuery / STEP）を生成する AI Design Desk の"
     [:strong "公開面（appview）"]
     " である。生成そのもの —— Zero-To-CAD 推論・"
     "CadQuery 実行・tsukuru へのハンドオフ・ライセンス門 —— は "
     [:span {:class "ad-mono"} "kotodama"]
     " 側の primitive にあり、ここには 1 行も無い。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "ad-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"
     [:span {:class "ad-mono"} " OPTIONS /xrpc/*"] " は CORS preflight（204）。"])

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div
       (into [:p] (interpose " " (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "ad-note"}
        "キー名のみ。ただし "
        [:strong "下の中継先だけは値そのもの"]
        "（"
        [:span {:class "ad-mono"} "AGENTGATEWAY_MCP_ROUTER_URL"]
        "）—— どこへ中継するかは運用者が見る必要があるので意図的に出している。"
        "それ以外の値は出さない。"]]
      [:p {:class "ad-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "ad-note"} "XRPC の中継先: "
     [:span {:class "ad-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "ad-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。中継先が解決しない場合、"
     [:span {:class "ad-mono"} "/xrpc/"]
     " は 502 を返す —— 到達できなかったことを 200 で隠さない。"])))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す（ライブラリは I/O を持たない）。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "aidesk — AI Design Desk"
    :description "画像・テキストから CAD を生成する AI Design Desk の公開面（appview）。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
