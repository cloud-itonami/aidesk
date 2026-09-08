#!/usr/bin/env nbb
;; verify-docs-claims — README.md と docs/operator-quickstart.md が述べる数値・存在・
;; 不在を tree から**引き直して**照合し、食い違えば落ちる。
;;
;; 移行前、この repo の load-bearing な事実は GAP だった: deploy される Worker は
;; SvelteKit のビルド出力（tree に無い）で、アプリらしく読める src/app.ts はどの
;; bundle にも入っていなかった。その gap は閉じたので、claim は**閉じたこと**を
;; 主張する。そして黙って戻らないように、TypeScript は「バイト合計に現れない」では
;; なく**名指しで不在**を検査する。
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> は先頭、既定 ".")
;; Exit:   0 全 claim が成立 · 1 claim が偽 · 2 答えられなかった
;;
;; ⚠ <dir> は引数の**先頭**に置く。多くの gate が
;; `(first (remove #(str/starts-with? % "--") argv))` で対象を決めるので、
;; `--flag . ` の順にすると flag の値が path として読まれる。

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[cljs.reader :as reader]
         '[kotoba.lang.text :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/aidesk-a1d3sk00")

(def claims
  {:tracked-files 20
   :inherited-bytes 5730          ; 継承した 5 ファイルが今も持っているバイト数
   :svelte-artifacts 0            ; .svelte / svelte.config / svelte/ ディレクトリ
   :sveltekit-compat-flags 0      ; nodejs_compat / nodejs_als は adapter-cloudflare の要求だった
   :production-ts-files 0
   :canonical-source-files 4      ; src/ と test/ の .cljc/.cljs
   :declared-vars 10
   :declared-routes 2
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export 'aidesk.worker/handler})

;; 抽出時から **1 バイトも変わっていない**ファイル。CLAUDE.md はこの集合から
;; 外してある —— 移行が偽にした記述（SvelteKit のビルド手順・deploy コマンド）を
;; 直したので、hash ではなく**内容**で検査する。wrangler.jsonc も同じ理由で
;; 内容検査（意図した変更と勝手な変更を区別するため）。
(def preserved
  {"NOTICE" "007f6c7c849455c00c23f0b1be4208db5cc9c7378330e0d82d75c4061d07dd47"
   "README.edn" "0486dacf6c642ed012a4700d5dcb1349604b33e99a981fa287d1dfad14e12dbd"
   "migration.edn" "397d205d4501175a8de301a5ed97c6d5788a125cae87ac944d156c6499e3c93f"
   "kotodama.jsonld" "c3b6202a326593a75f4498e702b2243d72968ce440149db4c174eff9a15ebbb1"
   "appview/aidesk-a1d3sk00/kotodama.jsonld"
   "c3b6202a326593a75f4498e702b2243d72968ce440149db4c174eff9a15ebbb1"})

;; 移行が撤去したもの、名指しで。バイト合計は「TypeScript が消えた」と言えない。
(def removed-by-migration
  ["appview/aidesk-a1d3sk00/package.json"
   "appview/aidesk-a1d3sk00/src/app.ts"
   "appview/aidesk-a1d3sk00/svelte/package.json"
   "appview/aidesk-a1d3sk00/svelte/src/app.html"
   "appview/aidesk-a1d3sk00/svelte/src/routes/+page.svelte"
   "appview/aidesk-a1d3sk00/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/aidesk-a1d3sk00/svelte/svelte.config.js"
   "appview/aidesk-a1d3sk00/svelte/tsconfig.json"
   "appview/aidesk-a1d3sk00/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; TypeScript は名指しで不在。撤去した 9 パスが戻れば落ちる。
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; **別名**で戻る場合を別の claim が捕まえる（.ts が 1 本でもあれば落ちる）。
    (check! :production-ts-files (:production-ts-files claims)
            (count (filter #(str/ends-with? % ".ts") files)))

    ;; Svelte がどんな名前でも戻らないこと。
    (check! :svelte-artifacts (:svelte-artifacts claims)
            (count (filter #(or (str/ends-with? % ".svelte")
                                (str/includes? % "svelte.config")
                                (str/includes? % "/svelte/"))
                           files)))

    ;; 正本言語の本数（src/ と test/ のみ。scripts/ と docs/ の nbb 道具は数えない）
    (check! :canonical-source-files (:canonical-source-files claims)
            (count (filter #(and (re-find #"^(src|test)/" %)
                                 (re-find #"\.(cljs|cljc|clj|kotoba)$" %))
                           files)))

    ;; CLAUDE.md が移行で偽になった記述を持たないこと。
    ;;
    ;; **『Svelte という語を含まない』では見ない。** 初版はそう書いていて、
    ;; 「TypeScript/Svelte から移行した」という**正しい歴史の記述**で落ちた ——
    ;; 部分文字列の禁止は散文についての検査であって、tree についての検査ではない
    ;; （同じ誤りを :page-renders-route-table でも避けている）。見るのは偽になった
    ;; 主張そのもの: 消えたビルド出力を指す手順と、存在しない CLI での deploy。
    (let [c (slurp* "CLAUDE.md")]
      (if (nil? c)
        (undet! "CLAUDE.md unreadable")
        (check! :claude-md-describes-cljs true
                (and (not (str/includes? c "svelte/.svelte-kit"))
                     (not (str/includes? c "etzhayyim deploy --smoke-url https://"))
                     (str/includes? c "shadow-cljs")))))

    ;; deploy される bundle が、この tree のソースから作られること
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)
              ;; **EDN として読む。grep しない。** grep は自分のコメントに当たるので、
              ;; 「:warnings-as-errors と書いてある」ことと「shadow がそれを読む場所に
              ;; 在る」ことを区別できない —— この option が防ぐはずの、落ちようの無い
              ;; 検査そのものになる。
              build (try (get-in (reader/read-string sh) [:builds :worker])
                         (catch :default e (undet! (str "shadow-cljs.edn unparseable: " (.-message e))) nil))]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          ;; 消えた SvelteKit client を指す assets binding が残っていないこと
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :sveltekit-compat-flags (:sveltekit-compat-flags claims)
                  (count (filter #{"nodejs_compat" "nodejs_als"}
                                 (or (get j "compatibility_flags") []))))
          (when build
            (check! :shadow-output-dir (:shadow-output-dir claims) (:output-dir build))
            (check! :shadow-export (:shadow-export claims)
                    (get-in build [:modules :worker :exports 'default]))
            (check! :wrangler-main-is-shadow-output true
                    (str/includes? (str (get j "main"))
                                   (str (:output-dir build) "/worker.js")))
            ;; 置き場所まで検査する。:build-options に置くと shadow は**黙って無視**する。
            (check! :warnings-as-errors-in-compiler-options true
                    (true? (get-in build [:compiler-options :warnings-as-errors])))
            (check! :warnings-as-errors-not-misplaced nil
                    (get-in build [:build-options :warnings-as-errors]))))))

    ;; ページは route 表を描く（固定値ではない）。構造で見て、部分文字列の禁止では
    ;; 見ない —— 「routeCount を含まない」形の検査は、旧欠陥を説明する docstring に
    ;; 当たって落ちる。コメントで落ちる検査は散文についての検査である。
    (let [v (slurp* "src/aidesk/view.cljc")
          w (slurp* "src/aidesk/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (check! :page-renders-route-table true
                (and (str/includes? v "[{:keys [routes vars mcp-url]}]")
                     (str/includes? v "(route-rows routes)")
                     (str/includes? w ":routes route/routes")))))

    ;; ADR は EDN tx-data（superproject の規約: 90-docs 面と同じ形）。実際に読めること。
    (let [adrs (filter #(re-find #"^docs/adr/.*\.edn$" %) files)]
      (if (empty? adrs)
        (undet! "no ADR found under docs/adr/")
        (check! :adr-edn-parses []
                (vec (keep (fn [f]
                             (try
                               ;; **ファイル全体**を読む。`read-string` は最初の form しか
                               ;; 読まないので、末尾に壊れたものを足しても素通りする ——
                               ;; 実測 2026-08-19、この検査の初版は trailing garbage を
                               ;; 当てても緑のままだった（**落ちようが無かった**）。
                               ;; `[...]` で包むと全 top-level form が 1 回で読まれ、
                               ;; 「ちょうど 1 つの tx-data である」ことも同時に言える。
                               (let [src (or (slurp* f) "")
                                     forms (reader/read-string (str "[" src "\n]"))
                                     d (first forms)]
                                 (cond
                                   (not= 1 (count forms))
                                   (str f " has " (count forms) " top-level forms, expected 1")
                                   (not (vector? d))
                                   (str f " top-level form is not tx-data (not a vector)")
                                   (not (and (map? (first d)) (:adr/id (first d))))
                                   (str f " parsed but is not adr tx-data")))
                               (catch :default e (str f " " (.-message e)))))
                           adrs)))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (if (seq f)
      ;; 偽だと分かったことは、測れなかったことより強い答えである。
      (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f))))
          (js/process.exit 1))
      (do (println "Refusing to report a pass: the tree could not be read completely.")
          (js/process.exit 2))))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
