# AGENTS.md

## 目的

本文件只定義修改邊界。功能與 MFi 語意以現有程式、測試及下列規格為準，不在此重複：

- `docs/mld_canonical_spec.md`
- `docs/mld_mapping_spec.md`
- `src_test/`

不要根據推測擴充解析或播放行為。

## 固定資料流

```text
MLD bytes
→ mld.format
→ mld.decode
→ mld.semantic.NativeProgram
   ├─ midi  → MidiPlan → MIDI
   └─ audio → PCM
→ playback / export / main
```

新功能必須沿用這條資料流，不得建立平行 parser、平行 semantic model，或從 UI／export 直接解讀原始事件。

## Package 邊界

- `mld.format`：只負責容器結構與原始資料。
- `mld.decode`：只負責事件 framing、解碼與 descriptor matching。
- `mld.semantic`：只表達 MLD 原生語意；不得依賴 MIDI、Java Sound、Swing、播放設備或檔案匯出。
- `midi`：負責 `NativeProgram → MidiPlan` 投影，以及 `MidiPlan` 的分段與序列化；不得負責設備或檔案寫入。
- `audio`：負責已驗證 sampled-audio 的解碼、混音、PCM source 與時長估算；不得擁有 Java Sound 設備。
- `normalize`：保留 machine-dependent forensic normalization，CLI 仍有 production 用途。
- `playback`：負責 MIDI／PCM transport、設備與程序生命週期。
- `export`：負責使用者檔案輸出。
- `main`：負責 CLI、Swing 與應用編排，不得自行解析 MLD 語意。
- `src_test`：只放測試、fixture 與 test support。

## 唯一 Owner

- `NativeCompiler`：原生事件排序與語意編譯入口。
- `MidiSequenceEncoder`：唯一的 `MidiPlan → Sequence` serializer。
- `MidiPlanSegmenter`：唯一的匯出用 MIDI 區段切割與起始狀態補齊 owner；不得負責序列化或寫檔。
- `ExportService`：唯一的使用者匯出與檔案寫入入口，包括完整／intro／loop MIDI artifact 編排。
- `MldApplicationWorkflow`：CLI／Swing 共用的 load → decode → compile → project 流程。
- `MidiOutputCatalog`：MIDI 設備列舉。
- `FluidSynthBackend`：FluidSynth 程序與協定。
- `PcmOutputConnection`：Java Sound output line。

修改時應擴充既有 owner，不得新增第二套入口、旁路或相容層。

## 語意修改規則

- 新增或改變 MFi 語意必須有程式碼證據、逆向證據或可復現樣本，並加入 deterministic regression。
- 未驗證的 sampled-audio／machine-dependent 路徑必須保留為 typed evidence 或 structured diagnostic；不得猜測渲染。
- 部分可辨識但無法可靠執行的音訊路徑維持 fail-closed。
- 修正既有行為時直接修正 authoritative path，刪除被取代的舊路徑。
- 新 abstraction 必須有當前 production caller；不要為未出現的需求預建框架。
- 保持 immutable model 邊界，不得重新暴露可變 byte array 或集合。

## 修改與驗證

- 使用 Java 8；不得引入較新語法或 API。
- 優先使用現有相依套件，不要重複實作已有能力。
- 修改範圍保持最小，避免順手重構無關程式。
- 缺陷修復必須加入能重現缺陷的測試。
- 完成後執行 `ant clean jar`；不得略過 architecture audit 或失敗測試。
