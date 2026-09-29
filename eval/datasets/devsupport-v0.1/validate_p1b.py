#!/usr/bin/env python3
"""
P1-B 综合验证脚本（v3.1）。

检查项：
  1. 语料哈希：7 份文档 SHA-256 与 corpus-manifest.json 一致
  2. Evidence 引句：全部逐字命中语料（归一化后）
  3. Chunk 完整性：chunk 无 U+FFFD，chunkId 唯一
  4. Evidence→Chunk 映射：所有 evidence 已映射，无悬空引用
  5. 重复运行校验：运行两次完整流水线（chunker → mapper → gold builder），
     比较全部 4 个交付工件 SHA-256，finally 恢复
  6. Candidate Gold：可答题有 gold 且 supportingChunkIds 非空，无答案题无正例 gold
  7. Tika 验证退出码：实际运行 TikaDifferenceVerifier，记录退出码
"""

import json
import hashlib
import subprocess
import sys
import tempfile
import shutil
from pathlib import Path

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()

def normalize_text(text):
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    lines = text.split('\n')
    lines = [line.rstrip() for line in lines]
    return '\n'.join(lines)

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    base_dir = Path(__file__).parent
    results = []

    # 1. 语料哈希校验
    print('=== 1. 语料哈希校验 ===')
    corpus_dir = base_dir / 'corpus'
    with open(base_dir / 'corpus-manifest.json', 'r', encoding='utf-8') as f:
        corpus_manifest = json.load(f)

    hash_ok = True
    for doc in corpus_manifest['documents']:
        doc_id = doc['docId']
        file_path = corpus_dir / f'{doc_id}.md'
        actual_hash = sha256_file(file_path)
        expected_hash = doc['originalSha256']
        if actual_hash == expected_hash:
            print(f'  [PASS] {doc_id}')
        else:
            print(f'  [FAIL] {doc_id}: expected {expected_hash[:12]}, got {actual_hash[:12]}')
            hash_ok = False
    results.append(('语料哈希', hash_ok))
    print()

    # 2. Evidence 引句校验
    print('=== 2. Evidence 引句校验 ===')
    with open(base_dir / 'queries.draft.json', 'r', encoding='utf-8') as f:
        queries = json.load(f)

    corpus_texts = {}
    for doc in corpus_manifest['documents']:
        doc_id = doc['docId']
        with open(corpus_dir / f'{doc_id}.md', 'r', encoding='utf-8') as f:
            corpus_texts[doc_id] = f.read()

    evidence_ok = True
    total_evidence = 0
    for query in queries['queries']:
        for ev in query.get('evidence', []) + query.get('nearMissEvidence', []):
            total_evidence += 1
            doc_id = ev['docId']
            quote = ev['quote']
            corpus_text = corpus_texts.get(doc_id, '')
            normalized_corpus = normalize_text(corpus_text)
            normalized_quote = normalize_text(quote)

            if quote in corpus_text or normalized_quote in normalized_corpus:
                pass
            else:
                print(f'  [FAIL] {query["queryId"]}: quote not found in {doc_id}')
                print(f'    quote: {quote[:60]}...')
                evidence_ok = False

    print(f'  总 evidence: {total_evidence}')
    print(f'  [{"PASS" if evidence_ok else "FAIL"}] 全部引句命中语料')
    results.append(('Evidence 引句', evidence_ok))
    print()

    # 3. Chunk 完整性
    print('=== 3. Chunk 完整性 ===')
    chunks = []
    with open(base_dir / 'chunks.jsonl', 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                chunks.append(json.loads(line))

    chunk_ids = [c['chunkId'] for c in chunks]
    unique_ids = set(chunk_ids)
    ffd_total = sum(c['text'].count('\ufffd') for c in chunks)

    chunk_ok = len(chunk_ids) == len(unique_ids) and ffd_total == 0
    print(f'  总 chunks: {len(chunks)}')
    print(f'  chunkId 唯一: {len(unique_ids) == len(chunk_ids)}')
    print(f'  U+FFFD 总数: {ffd_total}')
    print(f'  [{"PASS" if chunk_ok else "FAIL"}] Chunk 完整性')
    results.append(('Chunk 完整性', chunk_ok))
    print()

    # 4. Evidence→Chunk 映射
    print('=== 4. Evidence→Chunk 映射 ===')
    with open(base_dir / 'evidence-chunk-map.json', 'r', encoding='utf-8') as f:
        emap = json.load(f)

    all_chunk_ids = set(chunk_ids)
    mapping_ok = True
    dangling_refs = 0
    unmapped = 0

    for m in emap['mappings']:
        for cid in m.get('mappedChunkIds', []):
            if cid not in all_chunk_ids:
                dangling_refs += 1
                mapping_ok = False
        if m['status'] == 'unmapped':
            unmapped += 1

    print(f'  悬空引用: {dangling_refs}')
    print(f'  未映射: {unmapped}')
    print(f'  [{"PASS" if mapping_ok and unmapped == 0 else "FAIL"}] 映射完整性')
    results.append(('映射完整性', mapping_ok and unmapped == 0))
    print()

    # 5. 重复运行校验（完整流水线：chunker → mapper → gold builder，比较全部工件，finally 恢复）
    print('=== 5. 重复运行校验 ===')
    project_root = base_dir.parent.parent.parent  # eval/datasets/devsupport-v0.1 → project root

    artifact_names = ['chunks.jsonl', 'chunk-manifest.json', 'evidence-chunk-map.json', 'candidate-gold.json']
    saved_artifacts = {}
    repeatability_ok = False

    python_cmd = sys.executable

    def run_full_pipeline(label):
        """Run chunker → mapper → gold builder, return True on success."""
        gradle_cmd = [str(project_root / ('gradlew.bat' if sys.platform == 'win32' else 'gradlew'))]

        r = subprocess.run(
            gradle_cmd + [':app:evalOfflineChunk', '--no-daemon', '-q'],
            cwd=str(project_root),
            capture_output=True, text=True, timeout=300,
            encoding='utf-8', errors='replace'
        )
        if r.returncode != 0:
            print(f'  [FAIL] {label} evalOfflineChunk 失败 (exit={r.returncode})')
            print(f'    stderr: {r.stderr[:200]}')
            return False

        r_map = subprocess.run(
            [python_cmd, 'evidence_chunk_mapper.py'],
            cwd=str(base_dir),
            capture_output=True, text=True, timeout=60,
            encoding='utf-8', errors='replace'
        )
        if r_map.returncode != 0:
            print(f'  [FAIL] {label} evidence_chunk_mapper.py 失败 (exit={r_map.returncode})')
            return False

        r_gold = subprocess.run(
            [python_cmd, 'candidate_gold_builder.py'],
            cwd=str(base_dir),
            capture_output=True, text=True, timeout=60,
            encoding='utf-8', errors='replace'
        )
        if r_gold.returncode != 0:
            print(f'  [FAIL] {label} candidate_gold_builder.py 失败 (exit={r_gold.returncode})')
            return False

        return True

    try:
        # 保存全部交付工件
        for name in artifact_names:
            saved_artifacts[name] = (base_dir / name).read_bytes()

        # 第一次完整流水线
        print('  运行 #1 (chunker → mapper → gold builder) ...')
        if run_full_pipeline('#1'):
            run1_hashes = {name: sha256_file(base_dir / name) for name in artifact_names}

            # 第二次完整流水线
            print('  运行 #2 (chunker → mapper → gold builder) ...')
            if run_full_pipeline('#2'):
                run2_hashes = {name: sha256_file(base_dir / name) for name in artifact_names}

                all_match = True
                for name in artifact_names:
                    match = run1_hashes[name] == run2_hashes[name]
                    status = 'PASS' if match else 'FAIL'
                    print(f'  [{status}] {name}: {run1_hashes[name][:24]}')
                    if not match:
                        all_match = False

                if all_match:
                    print(f'  [PASS] 两次流水线运行全部工件完全一致')
                    repeatability_ok = True
                else:
                    print(f'  [FAIL] 两次运行存在工件不一致')

    except subprocess.TimeoutExpired:
        print('  [FAIL] 运行超时（300s）')
    except Exception as e:
        print(f'  [FAIL] 异常: {e}')
    finally:
        # 恢复全部工件
        for name, content in saved_artifacts.items():
            (base_dir / name).write_bytes(content)
        if saved_artifacts:
            print(f'  已恢复 {len(saved_artifacts)} 个工件')

    results.append(('重复运行校验', repeatability_ok))
    print()

    # 6. Candidate Gold
    print('=== 6. Candidate Gold ===')
    with open(base_dir / 'candidate-gold.json', 'r', encoding='utf-8') as f:
        gold = json.load(f)

    answerable_with_gold = 0
    no_answer_without_gold = 0
    empty_supporting = 0

    for entry in gold['entries']:
        if entry['answerability'] == 'ANSWERABLE':
            cg = entry.get('candidateGold')
            if cg is not None:
                answerable_with_gold += 1
                for ap in cg.get('answerPoints', []):
                    if len(ap.get('supportingChunkIds', [])) == 0:
                        empty_supporting += 1
                        print(f'  [FAIL] {entry["queryId"]} answerPoint#{ap["answerPointIndex"]}: supportingChunkIds 为空')
                all_ids = cg.get('allCandidateChunkIds', [])
                if len(all_ids) == 0:
                    print(f'  [FAIL] {entry["queryId"]}: allCandidateChunkIds 为空')
                    empty_supporting += 1
            else:
                print(f'  [FAIL] {entry["queryId"]}: ANSWERABLE but no gold')
        else:
            if entry.get('candidateGold') is None:
                no_answer_without_gold += 1
            else:
                print(f'  [FAIL] {entry["queryId"]}: NO_ANSWER but has gold')

    gold_ok = (answerable_with_gold == 16 and no_answer_without_gold == 4
               and empty_supporting == 0)
    print(f'  可答题有 gold: {answerable_with_gold}/16')
    print(f'  无答案无 gold: {no_answer_without_gold}/4')
    print(f'  空 supportingChunkIds: {empty_supporting}')
    print(f'  [{"PASS" if gold_ok else "FAIL"}] Candidate Gold 完整性')
    results.append(('Candidate Gold', gold_ok))
    print()

    # 7. Tika 验证退出码（实际运行 TikaDifferenceVerifier）
    print('=== 7. Tika 验证退出码 ===')
    tika_exit_code = None
    tika_ok = False
    try:
        if sys.platform == 'win32':
            gradle_cmd = [str(project_root / 'gradlew.bat')]
        else:
            gradle_cmd = [str(project_root / 'gradlew')]

        r_tika = subprocess.run(
            gradle_cmd + [':app:evalVerifyTika', '--no-daemon', '-q'],
            cwd=str(project_root),
            capture_output=True, text=True, timeout=300,
            encoding='utf-8', errors='replace'
        )
        tika_exit_code = r_tika.returncode
        print(f'  TikaDifferenceVerifier 退出码: {tika_exit_code}')

        # 与 manifest 中记录的退出码比对
        with open(base_dir / 'chunk-manifest.json', 'r', encoding='utf-8') as f:
            manifest = json.load(f)
        recorded_exit_code = manifest.get('parsingPolicy', {}).get('tikaVerification', {}).get('exitCode')
        if recorded_exit_code is not None:
            if tika_exit_code == recorded_exit_code:
                print(f'  [PASS] 与 manifest 记录一致 (exitCode={recorded_exit_code})')
                tika_ok = True
            else:
                print(f'  [FAIL] manifest 记录 exitCode={recorded_exit_code}，实际 exitCode={tika_exit_code}')
        else:
            print(f'  [WARN] manifest 未记录 exitCode')
            tika_ok = True

        if r_tika.returncode == 0:
            print(f'  Tika 对全部 .md 语料无差异')
        else:
            print(f'  Tika 对部分 .md 语料存在差异（已据实记录）')
            if r_tika.stdout.strip():
                for line in r_tika.stdout.strip().split('\n')[-3:]:
                    print(f'    {line.strip()}')

    except subprocess.TimeoutExpired:
        print('  [FAIL] 运行超时（300s）')
    except Exception as e:
        print(f'  [FAIL] 异常: {e}')

    results.append(('Tika 验证退出码', tika_ok))
    print()

    # 汇总
    print('=== 验证汇总 ===')
    all_pass = all(ok for _, ok in results)
    for name, ok in results:
        status = 'PASS' if ok else 'FAIL'
        print(f'  [{status}] {name}')

    print()
    if all_pass:
        print('所有检查通过。')
        return 0
    else:
        print('存在失败项，请检查。')
        return 1

if __name__ == '__main__':
    sys.exit(main())
