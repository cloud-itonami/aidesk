# aidesk — AI Design Desk の **エッジ面だけ**を持つ repo

名前が機能を示さないので最初に名乗る。**この repo は「画像・テキストから CAD を
生成するアプリ」ではない。** そのアプリ（AI Design Desk）の *前面* —— Cloudflare
Worker の BFF —— だけを持つ。CAD 合成そのもの（Zero-To-CAD 推論・CadQuery → STEP・
tsukuru へのハンドオフ・ライセンス門）はここには 1 行も無く、`kotodama` 側の
primitive にある。

`etzhayyim/root` の `60-apps/etzhayyim-project-aidesk` からの抽出物で、
**2026-08-19 に TypeScript/Svelte から ClojureScript へ移行した**（[ADR-0001](docs/adr/0001-migrate-the-appview-from-typescript-to-clojurescript.edn)）。
この README の数値はすべて `scripts/verify-docs-claims.cljs` が tree から再計算して
検査する —— 手打ちの値は無い。

## deploy されるものは、いま読んでいるソースである

```
src/aidesk/route.cljc    判断（どの handler が答えるか・封筒・ヘッダ）  ← 純 .cljc、テスト対象
src/aidesk/view.cljc     ページ（jp-go-dds の hiccup）                  ← 純 .cljc、テスト対象
src/aidesk/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js           ← wrangler.jsonc の "main" が指すもの
```

移行前は `main` が `svelte/.svelte-kit/cloudflare/_worker.js`（SvelteKit のビルド
出力。**tree に無い**）を指し、読み手が開く `appview/aidesk-a1d3sk00/src/app.ts` は
**どの bundle にも入っていなかった**（参照するコードが 0 件）。いまは `main` が指す
bundle が上のソースからコンパイルされたものなので、その形は構造的に起こり得ない。
`scripts/verify-docs-claims.cljs` が **shadow の出力先と wrangler の `main` と
export の ns 名が噛み合っていること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからである
（入口を当面 cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight（204） |

**この表の出所は `aidesk.route/routes` で、ページもそこから描く。** 移行前のページは
route 数・route 一覧・var 一覧を `+page.svelte` に literal で持っていた（最初は
`routeCount: 0`、2026-08-18 の c87bd2c が superproject の生成器で wrangler から
書き直したが、**生成器は別 repo にあり、ページは生成した瞬間の値を焼いたまま**）。
いまは worker が持つ表と env をページに渡すだけなので、両者がずれる余地が無い。

## 中継の形（移行前と同じ）

`POST /xrpc/<nsid>` は body を JSON-RPC 2.0 の封筒に包んで MCP router へ送り、
`result.structuredContent` を剥がして返す。移行で**変えなかった**もの:

- **多段パスを弾かない。** `/xrpc/a/b` は `a/b` という tool 名としてそのまま転送する
  （移行前の rest parameter `[...path]` と同じ）。**空の nsid だけが 400。**
  1 セグメントに絞るのは移行ではなく方針変更なので、この移行ではやらない。
- **NSID prefix の検査を足さない。** 配備されていた BFF に prefix フィルタは無く、
  `com.example.totally.unrelated.method` も転送されていた（`src/app.ts` には
  フィルタがあったが、その worker は起動しない）。
- **壊れた JSON body は握り潰して `{}` として転送する。**（400 にするのは方針変更）
- **受信ヘッダを転送する**（`host` と `content-length` を除く）。移行前は
  `new Headers(event.request.headers)` から `host` を落としていただけなので、
  `authorization` は上流へ届いていた。**3 本だけ送る実装に縮めると、認証つきの
  呼び出しが黙って認証を失う。**

変えたもの: 上流に名乗る `x-etzhayyim-bff` を `sveltekit-edge-bff` →
`cljs-edge-bff`。**これは上流から観測できる値**なので ADR に記録した（上流は今日
NXDOMAIN なので、実際に観測している者は居ない）。

## いま在るもの — 20 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/aidesk/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/aidesk/route_test.cljc`（7 tests / 34 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` |
| gate | `scripts/{smoke-worker.cljs, verify-docs-claims.cljs}` |
| Worker 設定 | `appview/aidesk-a1d3sk00/wrangler.jsonc` |
| actor 記述子 | `kotodama.jsonld`（root と appview に**バイト同一の 2 部**。抽出時から） |
| 設計 | `CLAUDE.md` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` |
| 文書・道具 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/*.edn` / `docs/mcp-router-stub.cljs` |

**production の TypeScript は 0 本、正本言語（`.cljc`/`.cljs`）が 4 本**
（`src/` と `test/`）。移行前は 3 対 0 だった（`src/app.ts` /
`svelte/src/routes/xrpc/[...path]/+server.ts` / `svelte/vite.config.ts`。
`+page.svelte` と `svelte.config.js` を数えれば 5 対 0）。この数は検証器の claim
なので、TS が戻れば落ちる —— 撤去した 9 パスに戻る場合（`removed-by-migration-absent`）も、
別名で入る場合（`production-ts-files`）も、別々の claim が捕まえる。

**appview ではない TypeScript は 1 本も無かった。** 「bundle に入らないが依存が
解決する別コンポーネント（domain library / SDK / Go・nginx の設定）は移行の対象外」
という規則はこの repo では適用対象が無い —— 測った結果、`.ts` は全部 appview のもの
だった。

## ページが出す値・出さない値

env の**キー名**は出すが、値は出さない —— **中継先を除いて**。
`AGENTGATEWAY_MCP_ROUTER_URL` の値だけは、どこへ中継するかを運用者が見る必要が
あるので意図的に表示する。

smoke はこれを**2 つの独立した印**で見る: 別の var に置いた sentinel が出て
いないこと、そして中継先の値が出ていること。**片方だけだと「全部隠す」実装も
「全部出す」実装も通ってしまう。**

## デザインシステムの検査は 2 本ある

`dads-table` が在ることを 1 本で見る形は**落ちない検査**だった —— それは view が
出力する markup であって、CSS が 1 バイトも入っていないページにも現れる。
実測（このページ、2026-08-19）:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **5**（0 にならない） |
| `class="dads-table"` | 1 | **1**（同じ） |
| `--color-primitive-blue` | 45 | **0** |

だから 2 本に割った。**component を使ったか**（`class="dads-table"`）と、
**stylesheet が実際に bundle へ入ったか**（`--color-primitive-blue`）は別の主張で
ある。`(rc/inline "jp_go_dds/dds.css")` を `""` にして**再ビルド**すると、後者だけが
赤くなり前者は緑のままであることを確認済み（ADR-0001 の mutation 表）。

design-quality のスコアはこの区別をしない —— 実測（このページ、12 軸）:
app CSS の `--hig-*` を raw な色（`rgba(…)` / `#5a5a5a`）と `13px` に置き換えても
**100.00 で PASS**、`<style>` を丸ごと空にして初めて **71.46 で FAIL** になる。
つまり「CSS が在るか」は測るが「トークン契約を守ったか」は測っていない。基盤は
`kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）で、色・寸法は
`--hig-*` トークン契約だけ、app 固有 CSS は 3 行、CSS は外部リクエストゼロの方針
どおり `shadow.resource/inline` で bundle に焼く。決定論的 audit で
**100.00 / 100（gate 95。既定は 10 軸、`--extra-axes` の 12 軸でも 100.00）**。

## 呼び先が 1 つも解決しない（移行では直らない）

`dig +short`（2026-08-19 実測）:

| ホスト | 役割 | DNS |
|---|---|---|
| `etzhayyim.com` | apex | 104.21.51.111 / 172.67.179.128 |
| `aidesk.etzhayyim.com` | 公開ホスト（wrangler の route） | **応答なし** |
| `a1d3sk00.etzhayyim.com` | 同（nanoid 側） | **応答なし** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **応答なし** |
| `dispatcher.etzhayyim.com` | 持ち越さなかった経路の宛先 | **応答なし** |

deploy 先も中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す**
（試した URL を本文に載せる）—— 成功と同じ形で隠さない。`did:web:aidesk.etzhayyim.com`
（`kotodama.jsonld` の `@id`）も同じ理由で解決しない。

## 持ち越さなかったもの（黙って消していない）

移行前の `src/app.ts` にあって**どこにも deploy されていなかった**経路のうち:

- **dispatcher 中継**（`dispatcher.etzhayyim.com` への POST）—— 宛先が NXDOMAIN で、
  かつ `DISPATCHER_URL` / `DISPATCHER_INTERNAL_SECRET` の binding が
  `wrangler.jsonc` の 10 個の var に**無い**。
- **`/_app/meta`** —— `/health` と同じ JSON を返していたが、その worker は起動しない
  （実測 404）。route 表を 1 本に保つため持ち越さず、404 のままにした。
- app.ts の `/health` が返していた **model / licenseTier / businessLogic のパス**
  —— **他 repo についての主張**で、この worker には確かめる手段が無い。

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。

### 逆に、足したもの

`GET /health` は**移行ではなく追加**である。配備されていた面では 404 だった
（`src/app.ts` は実装を持っていたが起動しない）。deploy された面が答えていることを
外から確かめる手段が要るので足し、**自分の route 表と受け取った nanoid だけ**を
名乗る形にした。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `a6b63ba`（14 files / 20,437
バイト）と宣言し、`:allowed-additions` に `README.edn` と `migration.edn` を持つ。
移行後の状態:

- 継承した 5 ファイル（5,730 バイト: `NOTICE` / `README.edn` / `migration.edn` /
  `kotodama.jsonld` ×2）は**いまも 1 バイトも変わっていない**（sha256 を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、消えた SvelteKit
  client を指す `assets` の撤去、`APP_FRAMEWORK` の更新、`compatibility_flags` の
  撤去。flags は workerd で実測してから外した）
- `CLAUDE.md` は移行が偽にした記述（消えたビルド出力を指す手順、PATH に無い CLI での
  deploy）を直した。hash ではなく**内容**で検査する
- TypeScript/Svelte の 9 ファイルは**移行で撤去**した。検証器はその 9 パスを名指しで
  「不在であること」を検査する —— バイト合計は「TS が消えた」と言えない

なお名前は 3 つ食い違ったままである（実際の置き場所 `cloud-itonami/aidesk` /
`README.edn` の `:name` が `com-etzhayyim-app-aidesk` / `migration.edn` の
`:destination` が `etzhayyim/com-etzhayyim-app-aidesk`）。**識別子としては west path
が正**で、EDN 側は履歴。移行はこれを直さない（継承ファイルを 1 バイトも触らないため）。

## 残っている欠陥（移行では直っていない）

1. **ホストが 4 つとも解決しない。** deploy しても誰も到達しないし、到達できても
   中継先が無い。deploy するか retire するかは別の決定。
2. **`kotodama.jsonld` が 2 部ある**（root と appview、バイト同一）。どちらが正本か
   宣言するものが無い。抽出時からの状態で、継承ファイルなので触っていない。
3. **`rules`（CompiledWasm）が inert なまま残っている。** この bundle は wasm を
   持たない。撤去は移行の判断ではないので触っていない。

## 検証

```bash
npx --yes nbb scripts/verify-docs-claims.cljs .    # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テスト・ビルド・smoke・workerd での実測は [`docs/operator-quickstart.md`](docs/operator-quickstart.md)。

## ライセンス

Apache 2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE` 参照）。
