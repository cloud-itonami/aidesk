# aidesk — AI Design Desk の **エッジ面だけ**を持つ repo

名前が機能を示さないので最初に名乗る。**この repo は「画像・テキストから CAD を
生成するアプリ」ではない。** そのアプリ（AI Design Desk）の *前面* ——
Cloudflare Worker + SvelteKit の BFF —— だけを持つ。CAD 合成そのもの
（Zero-To-CAD 推論・CadQuery → STEP・tsukuru へのハンドオフ・ライセンス門）は
ここには 1 行も無く、`kotodama` 側の primitive にある。

ここにあるのは実質 2 ファイル:

| ファイル | 何をするか | **配備されるか** |
|---|---|---|
| `appview/aidesk-a1d3sk00/svelte/src/routes/xrpc/[...path]/+server.ts` | POST された body を JSON-RPC 2.0 の `tools/call` に包んで MCP ルータへ転送し、`result.structuredContent` を剥がして返す | **される**（`wrangler.jsonc` の `main` が SvelteKit ビルドを指している） |
| `appview/aidesk-a1d3sk00/src/app.ts` | `/health`・`/_app/meta`・NSID prefix で絞った `/xrpc/*` → dispatcher プロキシ | **されない**（下記） |

手順は [`docs/operator-quickstart.md`](docs/operator-quickstart.md)。この README の
「実測」はすべてそこの手順を踏んだ結果で、手打ちの値は無い。

## 実測した現在地（2026-08-11）

### 1. 宣言しているホストは 4 つとも DNS に無い

```
etzhayyim.com             has address 104.21.51.111
aidesk.etzhayyim.com      NXDOMAIN
a1d3sk00.etzhayyim.com    NXDOMAIN
mcp.etzhayyim.com         NXDOMAIN
dispatcher.etzhayyim.com  NXDOMAIN
```

apex だけが生きている。したがって:

- `wrangler.jsonc` の `routes`（`a1d3sk00.etzhayyim.com/*` / `aidesk.etzhayyim.com/*`）は
  **どこも指していない**。この repo は production で動いていない。
- `kotodama.jsonld` の `@id` = `did:web:aidesk.etzhayyim.com` は
  **解決できない**（`did:web` は `https://<host>/.well-known/did.json` を引くので、
  ホストが無ければ DID document も無い）。
- 既定の上流 `https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message` が無いので、
  **素の状態では `POST /xrpc/*` が必ず 500 になる**。BFF が壊れているのではなく
  上流が無い。両者を切り分ける手段が quickstart の手順 4/5（ローカルスタブ）。

### 2. `src/app.ts` は配備されない

`wrangler.jsonc` の `main` は `svelte/.svelte-kit/cloudflare/_worker.js`（SvelteKit の
ビルド出力）で、`src/app.ts` を参照するものは **tracked file に 1 つも無い**。
ローカルで `wrangler dev` を上げて確認した結果:

```
GET /health      → 404 (text/html, SvelteKit の 404 ページ)
GET /_app/meta   → 404
```

`src/app.ts` はこの 2 つに JSON を返す実装を持っているが、その worker は起動しない。
結果として、次の 3 つは **効いていない**:

| `src/app.ts` にある約束 | 実際に配備されている BFF |
|---|---|
| NSID が `com.etzhayyim.apps.aidesk.` で始まらなければ 404 | **prefix フィルタが無い**。`POST /xrpc/com.example.totally.unrelated.method` が 200 で通り、そのまま上流へ転送される |
| 壊れた JSON body は 400 `InvalidJson` | `.catch(() => ({}))` で握り潰して `{}` として転送し 200 |
| `/health` が actor・model・licenseTier を JSON で返す | 404 |

`CLAUDE.md` に書かれている `etzhayyim deploy --smoke-url https://a1d3sk00.etzhayyim.com/health`
は、この設定が配備するものに対しては **404 を叩く**ことになる。
（`etzhayyim` CLI 自体はこのワークスペースの PATH に無く、deploy 経路は未検証。）

`src/app.ts` を消すか、`main` をそちらへ向けるか、あるいは BFF 側に prefix 検査を
足すかは**設計判断**なので、この repo を文書化するついでに決めない。ここでは
「どちらが動いているか」だけを固定する。

### 3. `npm run typecheck` は壊れている

`appview/aidesk-a1d3sk00/package.json` の `typecheck` は `tsc --noEmit` だが、
そのディレクトリに `tsconfig.json` が無く入力ファイルの指定も無いので、tsc は
**usage を印字して exit 1** する。つまり `src/app.ts` は誰にも型検査されていない。
ファイル自体は正しく、明示フラグを与えれば通る（quickstart 手順 6）。

型検査として実際に機能しているのは `svelte/` 側の `npm run check` だけ
（実測: 142 files / 0 errors / exit 0）。

## 動いている面（実測で確かめた振る舞い）

ローカルスタブを上流に据えて確認した:

| 入口 | 結果 |
|---|---|
| `GET /` | 200 `text/html`。SvelteKit の 1 枚ページ。ただし埋め込まれた `app` オブジェクトは `routeCount: 0` / `routes: []` / `vars: []` の**足場のまま**で、`wrangler.jsonc` の実際の 2 routes と 10 vars を映していない |
| `POST /xrpc/<nsid>` | 200。body を `{"jsonrpc":"2.0","id":<uuid>,"method":"tools/call","params":{"name":<nsid>,"arguments":<body>}}` に包んで転送し、`result.structuredContent` を剥がして返す。`x-etzhayyim-bff: sveltekit-edge-bff` / `x-etzhayyim-xrpc-method: <nsid>` を付ける |
| 上流が `{"error":{...}}` を返した | 502 + `{"error":<message>,"upstream":<全文>}` |
| `GET /xrpc/<nsid>` | 405（POST と OPTIONS しか無い） |
| `OPTIONS /xrpc/<nsid>` | 204 + CORS（`allow-origin: *` / `allow-methods: POST,OPTIONS`） |

`?embed=1` は `wrangler.jsonc` の `APP_EMBED_URL` が名乗るだけで、ページ側に
それを読む実装は無い。

## ここに無いもの（探しにくるのを止めるため）

`CLAUDE.md` が説明している次のものは、**すべて他 repo にある**:

- Zero-To-CAD 推論 / CadQuery 実行 / tsukuru ハンドオフ
  → `kotodama/primitives/aidesk.py`
- ライセンス門 `_tsukuru_handoff_gate()`（Apache-2.0 のみ商用 supplierExchange に
  到達でき、Autodesk Non-Commercial は `vertex_aidesk_research_artifact` に隔離される）
  → 同上。**この repo にはライセンス判定のコードが無い**ので、
  ここを直してもその境界は動かない
- graph table（`vertex_aidesk_design_job` 他）と BPMN 定義
- 設計の正本 ADR-2605051200 → `etzhayyim/root` の `90-docs/adr/`

## 素性（名前がずれている）

`migration.edn` のとおり、この repo は `etzhayyim/root` の
`60-apps/etzhayyim-project-aidesk`（tree `a6b63ba`、14 files / 20,437 bytes）を
切り出したもの。ただし 3 つの名前が食い違っている:

| どこ | 名前 |
|---|---|
| 実際の置き場所 | `cloud-itonami/aidesk`（west path `orgs/cloud-itonami/aidesk`） |
| `README.edn` の `:name` | `com-etzhayyim-app-aidesk` |
| `migration.edn` の `:destination` | `etzhayyim/com-etzhayyim-app-aidesk` |

repo は org ごと移った（`cloud-itonami`）が、切り出し時の metadata は
`etzhayyim` を指したまま。**識別子としては west path が正**で、EDN 側は履歴。
`README.edn`（`:canonical-metadata :edn`）は機械可読な metadata の正本であり、
この README.md はそれを置き換えるものではなく、人間向けの入口。

## ライセンス

Apache 2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE` 参照）。
