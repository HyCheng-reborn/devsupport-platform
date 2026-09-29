#!/usr/bin/env python3
"""
P1-B candidate gold builder (v2).

For each ANSWERABLE query:
  - Group evidence by supportsAnswerPoints
  - For each answer point, list chunkIds that directly support it
  - Validate: no ANSWERABLE query should have empty supportingChunkIds for any point
  - Distinguish "multiple answer points" from "truly needs multiple chunk combination"
  - A query truly needs multiple chunks if answer points require chunks from different documents
    or non-overlapping chunks that cannot be satisfied by a single chunk

For NO_ANSWER queries:
  - No positive gold
  - Record nearMissEvidence chunkIds for reference only
"""

import json
import sys
from pathlib import Path
from collections import defaultdict

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    base_dir = Path(__file__).parent

    with open(base_dir / 'evidence-chunk-map.json', 'r', encoding='utf-8') as f:
        emap = json.load(f)

    with open(base_dir / 'queries.draft.json', 'r', encoding='utf-8') as f:
        queries_data = json.load(f)

    # Build evidence lookup: (queryId, evidenceIndex) -> mapping
    ev_lookup = {}
    for m in emap['mappings']:
        key = (m['queryId'], m['evidenceIndex'])
        ev_lookup[key] = m

    gold_entries = []
    validation_errors = []

    for query in queries_data['queries']:
        qid = query['queryId']
        answerability = query['answerability']
        question = query['question']
        question_type = query['questionType']
        answer_points = query.get('expectedAnswerPoints', [])

        entry = {
            'queryId': qid,
            'question': question,
            'questionType': question_type,
            'answerability': answerability,
        }

        if answerability == 'NO_ANSWER':
            entry['candidateGold'] = None
            entry['reason'] = 'NO_ANSWER: 语料不包含答案'
            entry['missingFromCorpus'] = query.get('missingFromCorpus', '')
            entry['answerSourceOutsideCorpus'] = query.get('answerSourceOutsideCorpus', '')

            near_miss_chunks = []
            for ev_idx, ev in enumerate(query.get('nearMissEvidence', [])):
                m = ev_lookup.get((qid, ev_idx))
                if m and m['mappedChunkIds']:
                    near_miss_chunks.append({
                        'evidenceIndex': ev_idx,
                        'quote': ev['quote'],
                        'mappedChunkIds': m['mappedChunkIds'],
                        'note': 'nearMiss - 语料仅定性提及相关主题，不包含完整答案'
                    })
            if near_miss_chunks:
                entry['nearMissChunks'] = near_miss_chunks
        else:
            # ANSWERABLE: build candidate gold per answer point
            evidence_list = query.get('evidence', [])

            # Group evidence by answer point
            point_to_chunks = defaultdict(list)
            point_to_evidence = defaultdict(list)
            point_to_doc_ids = defaultdict(set)

            for ev_idx, ev in enumerate(evidence_list):
                m = ev_lookup.get((qid, ev_idx))
                if not m:
                    continue

                supported_points = ev.get('supportsAnswerPoints', [])
                chunk_ids = m.get('mappedChunkIds', [])
                doc_id = ev.get('docId', '')

                for pt in supported_points:
                    for cid in chunk_ids:
                        if cid not in point_to_chunks[pt]:
                            point_to_chunks[pt].append(cid)
                    point_to_doc_ids[pt].add(doc_id)
                    point_to_evidence[pt].append({
                        'evidenceIndex': ev_idx,
                        'docId': ev['docId'],
                        'quote': ev['quote'],
                        'chunkIds': chunk_ids,
                        'crossChunk': m.get('crossChunk', False),
                        'matchType': m.get('matchType'),
                    })

            # Build per-point gold and validate
            answer_point_gold = []
            has_empty_point = False

            for pt_idx, pt_text in enumerate(answer_points, 1):
                pt_key = pt_idx
                supporting_chunks = point_to_chunks.get(pt_key, [])
                
                if not supporting_chunks:
                    has_empty_point = True
                    validation_errors.append(f'{qid}: answerPoint {pt_idx} has no supporting chunks')

                gold_point = {
                    'answerPointIndex': pt_idx,
                    'answerPointText': pt_text,
                    'supportingChunkIds': supporting_chunks,
                    'supportingDocIds': sorted(point_to_doc_ids.get(pt_key, set())),
                    'supportingEvidenceCount': len(point_to_evidence.get(pt_key, [])),
                    'evidenceDetails': point_to_evidence.get(pt_key, []),
                }
                answer_point_gold.append(gold_point)

            # Determine if truly needs multiple chunk combination
            # Definition: 是否存在一个 chunk 同时属于所有答案要点的支持集合
            # If intersection of all answer points' supportingChunkIds is non-empty → single chunk suffices
            # If intersection is empty → no single chunk covers all points → multi-chunk required
            multi_chunk_needed = False
            multi_chunk_reason = ''

            if len(answer_points) > 1:
                point_chunk_sets = [set(pt['supportingChunkIds']) for pt in answer_point_gold]
                if point_chunk_sets:
                    common_chunks = point_chunk_sets[0]
                    for s in point_chunk_sets[1:]:
                        common_chunks = common_chunks & s

                    if not common_chunks:
                        multi_chunk_needed = True
                        total_chunks = sum(len(s) for s in point_chunk_sets)
                        multi_chunk_reason = f'无单个 chunk 同时属于所有 {len(answer_points)} 个答案要点的支持集合（共 {total_chunks} 个候选 chunk），需组合多个 chunk'

            entry['candidateGold'] = {
                'answerPoints': answer_point_gold,
                'multipleAnswerPoints': len(answer_points) > 1,
                'multipleChunkCombinationRequired': multi_chunk_needed,
                'combinationReason': multi_chunk_reason if multi_chunk_needed else '',
                'allCandidateChunkIds': sorted(set(
                    cid
                    for pt in answer_point_gold
                    for cid in pt['supportingChunkIds']
                )),
            }

            if has_empty_point:
                entry['validationError'] = '存在空的 supportingChunkIds'

        gold_entries.append(entry)

    # Summary statistics
    answerable_count = sum(1 for e in gold_entries if e['answerability'] == 'ANSWERABLE')
    no_answer_count = sum(1 for e in gold_entries if e['answerability'] == 'NO_ANSWER')
    
    # Count queries with multiple answer points
    multi_point_count = sum(
        1 for e in gold_entries
        if e.get('candidateGold') and e['candidateGold'].get('multipleAnswerPoints')
    )
    
    # Count queries that truly need multiple chunk combination
    multi_chunk_count = sum(
        1 for e in gold_entries
        if e.get('candidateGold') and e['candidateGold'].get('multipleChunkCombinationRequired')
    )
    
    # Count queries with validation errors
    error_count = sum(1 for e in gold_entries if e.get('validationError'))
    
    total_candidate_chunks = sum(
        len(e.get('candidateGold', {}).get('allCandidateChunkIds', []))
        for e in gold_entries
        if e.get('candidateGold')
    )

    output = {
        'goldPolicy': {
            'answerableRule': '每个 answerPoint 独立列出 supportingChunkIds；多要点题需组合命中，不可并集后"任意一个命中即答对"',
            'noAnswerRule': 'NO_ANSWER 题不给正例 gold；仅记录 nearMiss 的 chunk 引用供参考',
            'validationRule': 'ANSWERABLE 题的每个 answerPoint 必须有非空 supportingChunkIds，否则校验失败',
            'multiChunkDefinition': '真正需要多 chunk 组合 = 不存在一个 chunk 同时属于所有答案要点的支持集合（即各要点 supportingChunkIds 的交集为空）',
            'chunkIdSource': 'evidence→chunk 映射结果（evidence-chunk-map.json）',
        },
        'statistics': {
            'totalQueries': len(gold_entries),
            'answerableQueries': answerable_count,
            'noAnswerQueries': no_answer_count,
            'multipleAnswerPointQueries': multi_point_count,
            'multipleChunkCombinationQueries': multi_chunk_count,
            'validationErrors': error_count,
            'totalCandidateChunkReferences': total_candidate_chunks,
        },
        'validationErrors': validation_errors,
        'entries': gold_entries,
    }

    output_file = base_dir / 'candidate-gold.json'
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f'已写入: {output_file}')
    print()
    print('=== 候选 Gold 统计 ===')
    print(f'总查询: {len(gold_entries)}')
    print(f'  可答题: {answerable_count}')
    print(f'  无答案: {no_answer_count}')
    print(f'多答案要点题: {multi_point_count}')
    print(f'真正需要多 chunk 组合: {multi_chunk_count}')
    print(f'校验错误: {error_count}')
    print(f'候选 chunk 引用总数: {total_candidate_chunks}')

    if validation_errors:
        print()
        print('[!] 校验错误:')
        for err in validation_errors:
            print(f'  {err}')

    # Per-query summary
    print()
    for e in gold_entries:
        qid = e['queryId']
        ans = e['answerability']
        if ans == 'ANSWERABLE':
            gold = e['candidateGold']
            n_points = len(gold['answerPoints'])
            n_chunks = len(gold['allCandidateChunkIds'])
            multi_point = 'MULTI_PT' if gold['multipleAnswerPoints'] else 'SINGLE_PT'
            multi_chunk = 'MULTI_CHK' if gold['multipleChunkCombinationRequired'] else 'SINGLE_CHK'
            err = ' [ERROR]' if e.get('validationError') else ''
            print(f'{qid} | {ans:10s} | {multi_point:9s} | {multi_chunk:10s} | points={n_points} chunks={n_chunks}{err} | {e["question"][:35]}')
        else:
            nm = len(e.get('nearMissChunks', []))
            print(f'{qid} | {ans:10s} | NO_GOLD    | -          | nearMiss={nm} | {e["question"][:35]}')

if __name__ == '__main__':
    main()
