# etzhayyim-project-aidesk

AI Design Desk — 画像・テキストから CadQuery/STEP を生成し tsukuru 製造フローへ橋渡しする。

## App Identity

| Field | Value |
|---|---|
| nanoid | `a1d3sk00` |
| domain | `aidesk.etzhayyim.com` |
| AT bot DID | `did:web:aidesk.etzhayyim.com` |
| Primary DID | `did:erc725:etzhayyim:260505:{identityContract}` |
| Runtime | T3 CF Worker (thin edge) + T2 kotodama BPMN (LangServer) |
| NSID prefix (商用) | `com.etzhayyim.apps.aidesk.*` |
| NSID prefix (研究) | `com.etzhayyim.apps.aidesk.research.*` (Phase 2) |
| ADR | `90-docs/adr/2605051200-aidesk-cad-synthesis-actor.md` |

## CRITICAL: ライセンス境界

- **Apache 2.0** (`license_tier="apache2"`) のみが tsukuru 商用 supplierExchange に到達できる
- **Autodesk Non-Commercial** (`license_tier="adsk-noncommercial"`) は `vertex_aidesk_research_artifact` に隔離、tsukuru JOIN 不可
- `_tsukuru_handoff_gate()` が structural gate — soft check ではない

## Model

| Model | License | Phase |
|---|---|---|
| ADSKAILab/Zero-To-CAD-Qwen3-VL-2B | Apache 2.0 | Phase 1 (商用) |
| ADSKAILab/Make-A-Shape-* | Autodesk Non-Commercial | Phase 2 (研究のみ) |
| ADSKAILab/WaLa-* | Autodesk Non-Commercial | Phase 2 (研究のみ) |

## XRPC Commands

| NSID | Kind |
|---|---|
| `com.etzhayyim.apps.aidesk.submitDesignJob` | procedure |
| `com.etzhayyim.apps.aidesk.getDesignJob` | query |
| `com.etzhayyim.apps.aidesk.listDesignJobs` | query |
| `com.etzhayyim.apps.aidesk.exportToTsukuru` | procedure |

## tsukuru Integration

aidesk → (K8s-internal bpmn-dispatcher) → `com.etzhayyim.apps.tsukuru.supplierExchange.normalizePackage`

normalize/validate は tsukuru 既存 BPMN に委譲。aidesk 側で再実装しない。

## Build & Deploy

```bash
cd 60-apps/etzhayyim-project-aidesk/appview/aidesk-a1d3sk00
etzhayyim deploy --smoke-url https://a1d3sk00.etzhayyim.com/health
```

## Graph Tables

- `vertex_aidesk_design_job` — ジョブトラッキング (actor_did, license_tier, status)
- `vertex_aidesk_artifact` — 生成アーティファクト (step_b2_key, cadquery_code, license_tier)
- `edge_aidesk_job_artifact` — job → artifact エッジ
- `mv_aidesk_job_status` — ステータス集計 Streaming MV
- `vertex_aidesk_research_artifact` — 研究用 (Non-Commercial, 商用 table と JOIN 不可)

## kotodama Primitives

- `kotodama/primitives/aidesk.py`
  - `task_aidesk_cad_synthesize` — Zero-To-CAD inference
  - `task_aidesk_cad_execute` — CadQuery → STEP
  - `task_aidesk_tsukuru_handoff` — license gate + K8s-internal dispatch
