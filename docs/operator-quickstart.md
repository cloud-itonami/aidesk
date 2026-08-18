# operator quickstart — aidesk のエッジ面を手元で動かす

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 10 分。
Cloudflare のアカウントは要らない（deploy だけが要る。§5）。

**出力はすべて 2026-08-19 に実際に walk した結果で、手打ちの値は無い。**
踏めなかった手順（deploy）は最後に「未検証」として分けてある。

移行前（TypeScript/Svelte 版）の手順は git 履歴にある。この文書は移行後の面を扱う。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| Node.js | `node --version` | v26.3.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| clojure | `clojure --version` | ビルド時のみ |
| wrangler | `npx wrangler --version` | §4.6 のみ（`wrangler@latest`） |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/aidesk.git
cd aidesk
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の答えで、
「検査して問題なし」と混ぜない。

実際の出力（末尾）:

```
PASS	warnings-as-errors-in-compiler-options	expected=true	actual=true
PASS	warnings-as-errors-not-misplaced	expected=nil	actual=nil
PASS	page-renders-route-table	expected=true	actual=true
PASS	adr-edn-parses	expected=[]	actual=[]
OK	every claim in README.md and docs/operator-quickstart.md holds
```

この検査には移行の不変条件が入っている: TypeScript が戻っていないこと（撤去した
9 パスの不在 + `.ts` の総数）、`wrangler.jsonc` の `main` が shadow の出力先を指して
いること、`:warnings-as-errors` が **`:compiler-options` の下に在る**こと（EDN として
読んで確かめる。grep は自分のコメントに当たる）、ページが route 表から描かれること。

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
d=$(mktemp -d)
cat > "$d/run.cljs" <<'EOF'
(require '[cljs.test :refer [run-tests]] 'aidesk.route-test)
(run-tests 'aidesk.route-test)
EOF
npx --yes nbb --classpath "$CP" "$d/run.cljs"
```

実際の出力:

```
Testing aidesk.route-test

Ran 7 tests containing 34 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400 にする（`/xrpc/a/b` は移行前の
rest parameter と同じく転送する。1 セグメントに絞るのは移行ではなく方針変更）、
**受信ヘッダの転送**（`host` と `content-length` だけ落とし、`authorization` は
上流へ届く）、MCP router の URL 解決（空白だけの設定は未設定として扱う）、封筒の形、
`result` / `structuredContent` の剥がし方、そして**ページが route 表から描かれること**
（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > "$d/render.cljs" <<'EOF'
(require '["node:fs" :as fs] '[aidesk.view :as view] '[aidesk.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs (.-OUT js/process.env)
    (view/render {:css css :routes route/routes
                  :vars [:APP_NANOID :APP_UI_TYPE]
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" OUT="$d/page.html" npx --yes nbb --classpath "$CP" "$d/render.cljs"

cd $K/design-quality && npx --yes nbb -m design-quality.cli score "$d/page.html" --min 95
```

実際の出力（末尾）:

```
  100.00  /…/page.html
aggregate: 100.00
axes scored: 10 (viewport, safe-area, dynamic-viewport, tap-targets, focus-visible,
                 reduced-motion, overflow-guard, color-scheme, responsive, semantics)
NOT scored: input-zoom, contrast — pass --extra-axes to include the optional ones
gate: aggregate 100.00 >= min 95.00 -> PASS
```

`--extra-axes` を付けた 12 軸でも **100.00 / PASS**。

**このスコアが言わないこと**を先に書く（どれもこのページで実測した）:

| ページ | 12 軸スコア | gate(95) |
|---|---|---|
| そのまま | 100.00 | PASS |
| app CSS の `--hig-*` を raw な色（`rgba(…)` / `#5a5a5a`）と `13px` に置換 | **100.00** | **PASS** |
| viewport の meta を外す | 88.76 | FAIL |
| `<style>` を丸ごと空にする | 71.46 | FAIL |

つまり gate は無反応ではない（CSS が消えれば落ちる）が、**トークン契約を守ったか
どうかは測っていない**。「DADS の stylesheet が実際に bundle に入ったか」を言えるのは
§4.5 の smoke の 2 本目だけである。

## 4. bundle をビルドする

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の resource
governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

lock を他セッションが持っていると **exit 2 で拒否される**。**迂回しない** ——
`resource-guard: build is already running (pid=…)` はエラーではなく順番待ちである
（この walk では 5 回まで待った）。

実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 8.91s)
-rw-r--r--  1 junkawasaki  wheel  247975  dist/worker.js
d9782455ba74eca609963bfef49a563a2d0b11cf6a588004db2df1b203622b37  dist/worker.js
```

### 壊れた var はビルドを **落とす**（2026-08-19 実測）

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` を入れてある。
入れる前は、存在しない var を参照しても shadow は **WARNING** を出して **exit 0** し、
最初のリクエストで落ちる bundle を書いていた ——「ビルドが通った」は検査ではなかった
（**落ちようが無かった**）。

この repo で実際に落として確かめた。`src/aidesk/worker.cljs` の `route/dispatch` を
存在しない `route/dispatch-nonexistent` に改名して再ビルドする:

```
------ ERROR -------------------------------------------------------------------
Use of undeclared Var aidesk.route/dispatch-nonexistent
{:warning :undeclared-var, :line 114, :column 45, :shadow.build.compiler/warning-as-error true}
```

| | exit | `dist/worker.js` sha256 |
|---|---|---|
| 改名前 | **0** | `d9782455…03622b37` |
| 改名後 | **1** | `d9782455…03622b37`（**不変**） |
| 戻して再ビルド | **0** | `d9782455…03622b37` |

**落ちたビルドは bundle を出荷しない** —— sha256 が 1 バイトも動いていないことが
それを言っている。

キーは `:build-options` ではなく **`:compiler-options`** に置く。同じ壊れた var の
まま `:build-options` へ移して測った:

```
[:worker] Build completed. (55 files, 1 compiled, 1 warnings, 9.31s)   ← exit 0
sha256: f181a6d2…00389810                                              ← 別の bundle を出荷した
```

その bundle は最初のリクエストで
`Cannot read properties of undefined (reading 'h')` を投げ、§4.5 の smoke が exit 2 で
拒否した。**置き場所を間違えると shadow は黙って無視する** ——
この option が防ぐはずの失敗（落ちようの無い検査）そのものになるので、
`scripts/verify-docs-claims.cljs` は EDN を読んで**位置**を検査する。

## 4.5 ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

実際の出力（抜粋）:

```
PASS	default export has fetch	expected=true	actual=true
PASS	page uses the design system components	expected=true	actual=true
PASS	page carries the stylesheet itself	expected=true	actual=true
PASS	multi-segment nsid is relayed, not rejected	expected=200	actual=200
PASS	relay forwards authorization upstream	expected="Bearer smoke-token"	actual="Bearer smoke-token"
PASS	an unreachable upstream is 502, not 200	expected=502	actual=502
CHECKS	36
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）。
中継の検査は `globalThis.fetch` を差し替えて**実 DNS に依存せず**行う ——
上流が何を受け取るか（封筒・ヘッダ・多段パスの扱い）を bundle レベルで見る唯一の
手段で、到達不能の 502 だけは `.invalid`（RFC 2606 で必ず解決しない）への実 fetch で
確かめる。

## 4.6 Workers ランタイム（workerd）で動かす

Node で import する smoke より強い検査。実際の workerd で起こす。

```bash
cd "$REPO/appview/aidesk-a1d3sk00"
npx --yes wrangler@latest dev --local --port 8823 --ip 127.0.0.1
# 別シェルで
B=http://127.0.0.1:8823
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' $B/
curl -s $B/health; echo
curl -s -X POST $B/xrpc/ ; echo
curl -s -X POST -H 'content-type: application/json' -d '{}' $B/xrpc/a/b; echo
curl -s $B/nope; echo
```

実際の出力:

```
200 text/html; charset=utf-8
{"ok":true,"app":"aidesk","runtime":"cljs","nanoid":"a1d3sk00","routes":["/","/health","/xrpc/:nsid"]}
{"error":"Missing XRPC method"}
{"error":"MCP router unreachable","detail":"internal error; reference = ifo4bou75qjjgo3dbd0eisrc","url":"https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}
{"error":"Not Found","routes":["GET /","GET /health","POST /xrpc/:nsid"]}
```

`OPTIONS /xrpc/x` は 204。`GET /` のページには DADS の CSS が入っている
（`dads-table` 74 / `--color-primitive-blue` 45 を実測）。

`compatibility_flags`（`nodejs_compat` / `nodejs_als`）は SvelteKit の
adapter-cloudflare 由来で、この bundle には要らない。**撤去は憶測ではなくこの実測で
確かめてから行った。**

## 5. 上流のスタブを立てて、中継の形を目で見る

既定の上流 `mcp.etzhayyim.com` は NXDOMAIN なので、素の状態では `/xrpc/*` が必ず
502 になる。それが「BFF の不具合」なのか「上流が無いだけ」なのかを切り分けるために、
ローカルの上流を 1 つ立てる。

```bash
npx --yes nbb docs/mcp-router-stub.cljs --port 8796        # 成功応答を返す
# 別シェルで
cd "$REPO/appview/aidesk-a1d3sk00"
npx --yes wrangler@latest dev --local --port 8824 --ip 127.0.0.1 \
  --var AGENTGATEWAY_MCP_ROUTER_URL:http://127.0.0.1:8796/xrpc/com.etzhayyim.mcp.message
# さらに別シェルで
curl -s -X POST -H 'content-type: application/json' -H 'authorization: Bearer local-token' \
  -d '{"limit":5}' http://127.0.0.1:8824/xrpc/com.etzhayyim.apps.aidesk.listDesignJobs
```

実際の出力:

```
{"jobs":[],"stub":true,"echoNsid":"com.etzhayyim.apps.aidesk.listDesignJobs"}
```

スタブ側に出た受信ログ（**BFF が実際に送っている形**。移行前と同じ封筒で、
名乗りだけが `sveltekit-edge-bff` → `cljs-edge-bff` に変わっている）:

```
<- POST /xrpc/com.etzhayyim.mcp.message  jsonrpc.method="tools/call"
   params.name="com.etzhayyim.apps.aidesk.listDesignJobs"  bff-header="cljs-edge-bff"
   body: {"jsonrpc":"2.0","id":"30bad0ac-…","method":"tools/call",
          "params":{"name":"com.etzhayyim.apps.aidesk.listDesignJobs","arguments":{"limit":5}}}
```

**このとき `GET /` を開くと、ページの「XRPC の中継先」が
`http://127.0.0.1:8796/xrpc/com.etzhayyim.mcp.message` になっている**（実測）。
ページは渡された env を描いていて、値を焼いていない —— 移行前のページが
`+page.svelte` の literal を描いていたのと入れ替わった点である。

`--mode error` で起動すると上流エラーになり、BFF はそれを 502 に変換する
（smoke の `an upstream error becomes 502` が同じことを bundle レベルで見ている）。

止めるのは `Ctrl-C`。`.wrangler/` `node_modules/` は `.gitignore` 済み。

## 6. deploy（この walk では**踏んでいない**）

```bash
cd "$REPO/appview/aidesk-a1d3sk00"
npx wrangler deploy
```

**踏まなかった理由**（3 つとも 2026-08-19 の実測）:

1. `routes` が指す `a1d3sk00.etzhayyim.com` / `aidesk.etzhayyim.com` が **NXDOMAIN**。
   deploy が成功しても誰も到達できない。
2. `/xrpc/` の中継先 `mcp.etzhayyim.com` も NXDOMAIN。到達できたとしても中継は
   **502 を返す**（成功と同じ形で隠さない）。
3. superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
   許さない。

`CLAUDE.md` が以前書いていた `etzhayyim deploy --smoke-url …` の CLI は、この
ワークスペースの PATH に無い（移行に伴って CLAUDE.md を直した）。

## 7. ここに無いもの

- **dispatcher 中継 / `/_app/meta`** —— 移行前の `src/app.ts` にあり、どこにも
  deploy されていなかった経路。宛先が NXDOMAIN、または binding が `wrangler.jsonc` に
  無いので**持ち越していない**（README の「持ち越さなかったもの」）
- CAD 合成そのもの（`kotodama` の primitive にある）
- ライセンス門 `_tsukuru_handoff_gate()`（同上。**この repo にライセンス判定の
  コードは無い**ので、ここを直してもその境界は動かない）
