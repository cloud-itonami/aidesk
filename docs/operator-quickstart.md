# operator quickstart — aidesk のエッジ面をローカルで動かす

この repo が実際に配備する worker（SvelteKit BFF）を手元で起動し、**何が動いていて
何が動いていないか**を自分の目で確かめるまでの手順。所要 5〜10 分。

**この手順は 2026-08-11 に実際に踏んで書いた。**以下の出力はすべてその実行結果で、
手打ちの値は無い。踏めなかった手順（deploy）は最後に「未検証」として分けてある。

なぜ手順が長いか: この app の既定の上流（`mcp.etzhayyim.com`）は現在 DNS に無く、
**何もせずに起動すると `POST /xrpc/*` が必ず 500 になる**。それが「BFF の不具合」なのか
「上流が無いだけ」なのかを区別できないと、この repo は触れない。手順 3 で素の 500 を
見せ、手順 4〜5 でローカルの上流を立てて 200 にするのは、その切り分けのため。

---

## 0. 前提

| 道具 | 実測したバージョン | 用途 |
|---|---|---|
| Node.js | v26.3.0 | ビルド・wrangler |
| npm | 11.16.0 | 依存取得 |
| wrangler | 4.69.0（`npx wrangler`） | ローカル worker（miniflare） |
| nbb | — | 手順 4 の上流スタブ |

Cloudflare のアカウント・API token は **要らない**（`--local` は miniflare で完結する）。
`curl` と `host` を使う。

```bash
cd <この repo>
node --version && npm --version && npx wrangler --version
```

## 1. 上流ホストが生きているか先に見る

直さないが、**500 を見て慌てないために先に測る**。

```bash
for h in etzhayyim.com aidesk.etzhayyim.com a1d3sk00.etzhayyim.com \
         mcp.etzhayyim.com dispatcher.etzhayyim.com; do
  printf "%-28s " "$h"; host "$h" 2>&1 | head -1
done
```

2026-08-11 の実測:

```
etzhayyim.com                etzhayyim.com has address 104.21.51.111
aidesk.etzhayyim.com         Host aidesk.etzhayyim.com not found: 3(NXDOMAIN)
a1d3sk00.etzhayyim.com       Host a1d3sk00.etzhayyim.com not found: 3(NXDOMAIN)
mcp.etzhayyim.com            Host mcp.etzhayyim.com not found: 3(NXDOMAIN)
dispatcher.etzhayyim.com     Host dispatcher.etzhayyim.com not found: 3(NXDOMAIN)
```

apex 以外は無い。**この結果が変わっていたら、以下の 500 の説明はもう当てはまらない**
ので測り直すこと。

## 2. ビルド

`wrangler.jsonc` の `main` は `svelte/.svelte-kit/cloudflare/_worker.js` を指すので、
**先に SvelteKit をビルドしないと worker は存在しない**。

```bash
cd appview/aidesk-a1d3sk00/svelte
npm install
node <superproject>/scripts/resource-guard.mjs run build -- npm run build
```

`resource-guard.mjs` は必須（このワークスペースの規約 —— 高負荷ビルドは同時 1 本）。
superproject を持っていない環境では `npm run build` を直接呼んでよいが、
他のビルドと並走させないこと。

実測: `✓ built in 13.72s` → `Using @sveltejs/adapter-cloudflare ✔ done`。
`.svelte-kit/cloudflare/{_worker.js,client/}` ができる。

> npm 11 は install script を既定で止めるので `allow-scripts` の警告が 3 件出る
> （esbuild ×2 / workerd）。**ビルドは通る**ので、この手順では承認しなくてよい。

## 3. 素の状態で起動して 500 を見る

```bash
cd ..                      # appview/aidesk-a1d3sk00
npx wrangler dev --local --port 8794 --ip 127.0.0.1
```

別の端末から:

```bash
B=http://127.0.0.1:8794
curl -s -o /dev/null -w "GET  /            -> %{http_code} %{content_type}\n" $B/
curl -s -o /dev/null -w "GET  /health      -> %{http_code} %{content_type}\n" $B/health
curl -s -w "\n[%{http_code}]\n" -X POST -H 'content-type: application/json' -d '{}' \
  $B/xrpc/com.etzhayyim.apps.aidesk.listDesignJobs
```

実測:

```
GET  /            -> 200 text/html
GET  /health      -> 404 text/html
{"message":"Internal Error"}
[500]
```

読み方は 2 つ:

- **`/health` の 404 は上流とは無関係。** `src/app.ts` が `/health` を実装しているが、
  その worker は配備されない（`main` は SvelteKit を指している）。詳細は README。
- **`/xrpc/*` の 500 は上流が無いから。** wrangler のログに
  `Uncaught Error: internal error` が出て、`_server.ts.js:24` の `fetch(mcpRouterUrl(...))`
  で落ちている —— 手順 1 で見たとおり `mcp.etzhayyim.com` が NXDOMAIN。

> このマシンでは起動時に `EMFILE: too many open files, watch` が何度も流れるが、
> その後 `Ready on http://127.0.0.1:8794` まで進めば動く（並行セッションが多い環境の
> file descriptor 枯渇であって、この repo の問題ではない）。

`Ctrl-C` で止める。

## 4. 上流のスタブを立てる

```bash
nbb docs/mcp-router-stub.cljs            # 127.0.0.1:8795、成功応答を返す
```

実測: `mcp-router-stub: http://127.0.0.1:8795  mode=success`。
このスタブは受け取った JSON-RPC をそのまま標準出力に出す（BFF が何を送るかを見るため）。

## 5. スタブを指して起動し直す

```bash
npx wrangler dev --local --port 8794 --ip 127.0.0.1 \
  --var AGENTGATEWAY_MCP_ROUTER_URL:http://127.0.0.1:8795/xrpc/com.etzhayyim.mcp.message
```

```bash
B=http://127.0.0.1:8794
curl -s -w "\n[%{http_code}]\n" -X POST -H 'content-type: application/json' \
  -d '{"limit":5}' $B/xrpc/com.etzhayyim.apps.aidesk.listDesignJobs
```

実測:

```
{"jobs":[],"stub":true,"echoNsid":"com.etzhayyim.apps.aidesk.listDesignJobs"}
[200]
```

スタブ側に出た受信ログ（**BFF が実際に送っている形**）:

```
<- POST /xrpc/com.etzhayyim.mcp.message  jsonrpc.method="tools/call"
   params.name="com.etzhayyim.apps.aidesk.listDesignJobs"  bff-header="sveltekit-edge-bff"
   body: {"jsonrpc":"2.0","id":"61714d84-…","method":"tools/call",
          "params":{"name":"com.etzhayyim.apps.aidesk.listDesignJobs",
                    "arguments":{"limit":5}}}
```

つまり BFF は「POST body を `arguments` に入れて `tools/call` で包み、
`result.structuredContent` を剥がして返す」だけ。**NSID の検証も入力の検証もしない。**

### 5.1 境界も実際に叩く（ここが一番役に立つ）

```bash
# 上流のエラーは 502 に変換される
pkill -f mcp-router-stub.cljs
nbb docs/mcp-router-stub.cljs --mode error &
curl -s -w "\n[%{http_code}]\n" -X POST -H 'content-type: application/json' -d '{}' \
  $B/xrpc/com.etzhayyim.apps.aidesk.exportToTsukuru
```

```
{"error":"stub: upstream refused","upstream":{"jsonrpc":"2.0","id":"338e4d73-…",
 "error":{"code":-32000,"message":"stub: upstream refused"}}}
[502]
```

`--mode success` に戻してから、残り 4 つ:

| 叩くもの | 実測 | 意味 |
|---|---|---|
| `POST /xrpc/com.example.totally.unrelated.method` | **200**、そのまま上流へ転送 | 配備されている BFF に **NSID prefix フィルタが無い**（`src/app.ts` にはあるが、そちらは動いていない） |
| `POST /xrpc/…getDesignJob`（body 無し） | 200、`arguments:{}` | |
| `POST /xrpc/…getDesignJob -d '{oops'` | **200**（400 ではない） | 壊れた JSON を `.catch(() => ({}))` で握り潰す |
| `GET /xrpc/…listDesignJobs` | 405 `GET method not allowed` | POST と OPTIONS だけ |
| `OPTIONS /xrpc/…listDesignJobs` | 204 + `allow-methods: POST,OPTIONS` / `allow-origin: *` | CORS は全開 |

**上 3 行は「壊れている」とは限らない**（検証を上流の MCP ルータに寄せる設計はありうる）。
ここで固定したいのは、**その検証がこの repo には無い**という事実のほう。

## 6. 型検査

```bash
cd svelte && npm run check
```

実測: `COMPLETED 142 FILES 0 ERRORS 0 WARNINGS 0 FILES_WITH_PROBLEMS`、exit 0。
**これが本物の型検査。**

一方、appview 直下のものは壊れている:

```bash
cd .. && npm install && npm run typecheck; echo "exit=$?"
```

実測 `exit=1` —— そのディレクトリに `tsconfig.json` が無く入力の指定も無いので、
tsc が usage を印字して終わる。`src/app.ts` 自体は正しく、明示フラグなら通る:

```bash
npx tsc --noEmit --strict --target es2022 --module esnext \
  --moduleResolution bundler --lib es2022,webworker src/app.ts; echo "exit=$?"
```

実測 `exit=0`。**`npm run typecheck` を CI に繋いだら常に赤**なので、繋ぐ前に直すこと。

## 7. 後片付け

```bash
pkill -f mcp-router-stub.cljs
# wrangler は Ctrl-C
```

`node_modules/` `.svelte-kit/` `.wrangler/` `package-lock.json` は `.gitignore` 済みなので、
この手順のあと `git status` は綺麗なまま（実測で確認）。消したければ:

```bash
rm -rf appview/aidesk-a1d3sk00/node_modules appview/aidesk-a1d3sk00/.wrangler \
       appview/aidesk-a1d3sk00/svelte/node_modules appview/aidesk-a1d3sk00/svelte/.svelte-kit
```

---

## 踏んでいない手順（正直に）

- **deploy。** `CLAUDE.md` は
  `etzhayyim deploy --smoke-url https://a1d3sk00.etzhayyim.com/health` と書いているが、
  ① `etzhayyim` CLI がこのワークスペースの PATH に無い
  ② 宣言されている route の zone（`a1d3sk00.etzhayyim.com` / `aidesk.etzhayyim.com`）が
  NXDOMAIN で、配備先が存在しない
  ③ その smoke URL は、この設定が配備する worker では 404 を返す（手順 3）。
  **3 つとも直してからでないと deploy は意味を持たない**ので、手順に書かなかった。
- **上流の MCP ルータ / dispatcher / kotodama 側の実装。** この repo には無い。
  手順 4 のスタブは形を見るためのもので、実物の代わりではない。
