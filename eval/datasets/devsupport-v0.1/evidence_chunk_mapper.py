#!/usr/bin/env python3
"""
P1-B evidence→chunk mapper (v2: single-chunk priority, accurate cross-chunk).

Mapping logic:
  1. For each evidence quote, first try exact match in each chunk.
  2. If not found, try normalized match (CRLF→LF, strip trailing whitespace per line).
  3. Only if no single chunk contains the full quote, check adjacent chunk pairs
     within the same document (quote spans a chunk boundary).
  4. If still not found, mark as unmapped with reason.

Important: evidence quotes are from the ORIGINAL corpus files (before cleaning).
Chunks are produced from CLEANED text (TextCleaningService). Cleaning changes include:
  - CRLF → LF
  - Strip trailing whitespace per line
  - Compress 3+ consecutive newlines to 2
  - Remove image filenames, image URLs, separator lines, control chars

So we need normalization to match quotes against chunks.
"""

import json
import sys
from pathlib import Path

def normalize_text(text):
    """Normalize text for matching: CRLF→LF, strip trailing whitespace per line."""
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    lines = text.split('\n')
    lines = [line.rstrip() for line in lines]
    return '\n'.join(lines)

def load_chunks(chunks_file):
    """Load chunks from JSONL file."""
    chunks = []
    with open(chunks_file, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                chunks.append(json.loads(line))
    return chunks

def load_queries(queries_file):
    """Load queries with evidence from JSON file."""
    with open(queries_file, 'r', encoding='utf-8') as f:
        return json.load(f)

def find_quote_in_chunk(quote, chunk_text):
    """
    Try to find quote in chunk text.
    Returns: (found, match_type) where match_type is 'exact' or 'normalized'.
    """
    if quote in chunk_text:
        return True, 'exact'
    normalized_quote = normalize_text(quote)
    normalized_chunk = normalize_text(chunk_text)
    if normalized_quote in normalized_chunk:
        return True, 'normalized'
    return False, None

def map_evidence_to_chunks(chunks, queries_data):
    """
    Map all evidence items to chunks.
    Returns mapping results and statistics.
    """
    # Group chunks by docId
    chunks_by_doc = {}
    for chunk in chunks:
        doc_id = chunk['docId']
        if doc_id not in chunks_by_doc:
            chunks_by_doc[doc_id] = []
        chunks_by_doc[doc_id].append(chunk)

    # Sort chunks within each doc by seq
    for doc_id in chunks_by_doc:
        chunks_by_doc[doc_id].sort(key=lambda c: c['seq'])

    mappings = []
    stats = {
        'totalEvidence': 0,
        'mappedSingleChunk': 0,
        'mappedCrossChunk': 0,
        'unmapped': 0,
        'matchTypeExact': 0,
        'matchTypeNormalized': 0,
        'nearMissMapped': 0,
    }

    for query in queries_data['queries']:
        query_id = query['queryId']
        answerability = query['answerability']

        # Process regular evidence
        for ev_idx, evidence in enumerate(query.get('evidence', [])):
            stats['totalEvidence'] += 1
            doc_id = evidence['docId']
            quote = evidence['quote']

            mapping = {
                'queryId': query_id,
                'evidenceIndex': ev_idx,
                'docId': doc_id,
                'quote': quote,
                'quoteLength': len(quote),
                'supportsAnswerPoints': evidence.get('supportsAnswerPoints', []),
                'isNearMiss': False,
            }

            # Try to find in single chunks first
            found_chunks = []
            match_type = None

            if doc_id in chunks_by_doc:
                for chunk in chunks_by_doc[doc_id]:
                    found, mt = find_quote_in_chunk(quote, chunk['text'])
                    if found:
                        found_chunks.append(chunk['chunkId'])
                        match_type = mt

            if len(found_chunks) == 1:
                mapping['mappedChunkIds'] = found_chunks
                mapping['crossChunk'] = False
                mapping['matchType'] = match_type
                mapping['status'] = 'mapped_single'
                stats['mappedSingleChunk'] += 1
                if match_type == 'exact':
                    stats['matchTypeExact'] += 1
                else:
                    stats['matchTypeNormalized'] += 1
            elif len(found_chunks) > 1:
                # Quote appears in multiple chunks (could be duplicate content)
                # This is unusual but possible if the same text appears in different places
                mapping['mappedChunkIds'] = found_chunks
                mapping['crossChunk'] = False
                mapping['matchType'] = match_type
                mapping['status'] = 'mapped_multiple_candidates'
                mapping['note'] = 'Quote appears in multiple chunks; all are candidates'
                stats['mappedSingleChunk'] += 1
                if match_type == 'exact':
                    stats['matchTypeExact'] += 1
                else:
                    stats['matchTypeNormalized'] += 1
            else:
                # Not found in any single chunk; try adjacent chunk pairs
                cross_found = False
                if doc_id in chunks_by_doc:
                    doc_chunks = chunks_by_doc[doc_id]
                    for i in range(len(doc_chunks) - 1):
                        chunk_a = doc_chunks[i]
                        chunk_b = doc_chunks[i + 1]
                        # Try combining adjacent chunks
                        combined = chunk_a['text'] + chunk_b['text']
                        found, mt = find_quote_in_chunk(quote, combined)
                        if found:
                            mapping['mappedChunkIds'] = [chunk_a['chunkId'], chunk_b['chunkId']]
                            mapping['crossChunk'] = True
                            mapping['matchType'] = mt
                            mapping['status'] = 'mapped_cross_chunk'
                            mapping['note'] = 'Quote spans chunk boundary'
                            stats['mappedCrossChunk'] += 1
                            if mt == 'exact':
                                stats['matchTypeExact'] += 1
                            else:
                                stats['matchTypeNormalized'] += 1
                            cross_found = True
                            break

                if not cross_found:
                    mapping['mappedChunkIds'] = []
                    mapping['crossChunk'] = False
                    mapping['matchType'] = None
                    mapping['status'] = 'unmapped'
                    mapping['reason'] = 'Quote not found in any chunk or adjacent chunk pair'
                    stats['unmapped'] += 1

            mappings.append(mapping)

        # Process nearMissEvidence
        for ev_idx, evidence in enumerate(query.get('nearMissEvidence', [])):
            stats['totalEvidence'] += 1
            doc_id = evidence['docId']
            quote = evidence['quote']

            mapping = {
                'queryId': query_id,
                'evidenceIndex': ev_idx,
                'docId': doc_id,
                'quote': quote,
                'quoteLength': len(quote),
                'supportsAnswerPoints': evidence.get('supportsAnswerPoints', []),
                'isNearMiss': True,
            }

            # Try to find in single chunks
            found_chunks = []
            match_type = None

            if doc_id in chunks_by_doc:
                for chunk in chunks_by_doc[doc_id]:
                    found, mt = find_quote_in_chunk(quote, chunk['text'])
                    if found:
                        found_chunks.append(chunk['chunkId'])
                        match_type = mt

            if found_chunks:
                mapping['mappedChunkIds'] = found_chunks
                mapping['crossChunk'] = False
                mapping['matchType'] = match_type
                mapping['status'] = 'mapped_single'
                stats['nearMissMapped'] += 1
                stats['mappedSingleChunk'] += 1
                if match_type == 'exact':
                    stats['matchTypeExact'] += 1
                else:
                    stats['matchTypeNormalized'] += 1
            else:
                # Try adjacent chunks
                cross_found = False
                if doc_id in chunks_by_doc:
                    doc_chunks = chunks_by_doc[doc_id]
                    for i in range(len(doc_chunks) - 1):
                        chunk_a = doc_chunks[i]
                        chunk_b = doc_chunks[i + 1]
                        combined = chunk_a['text'] + chunk_b['text']
                        found, mt = find_quote_in_chunk(quote, combined)
                        if found:
                            mapping['mappedChunkIds'] = [chunk_a['chunkId'], chunk_b['chunkId']]
                            mapping['crossChunk'] = True
                            mapping['matchType'] = mt
                            mapping['status'] = 'mapped_cross_chunk'
                            stats['nearMissMapped'] += 1
                            stats['mappedCrossChunk'] += 1
                            if mt == 'exact':
                                stats['matchTypeExact'] += 1
                            else:
                                stats['matchTypeNormalized'] += 1
                            cross_found = True
                            break

                if not cross_found:
                    mapping['mappedChunkIds'] = []
                    mapping['crossChunk'] = False
                    mapping['matchType'] = None
                    mapping['status'] = 'unmapped'
                    mapping['reason'] = 'Near-miss quote not found in chunks'
                    stats['unmapped'] += 1

            mappings.append(mapping)

    return mappings, stats

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    base_dir = Path(__file__).parent
    chunks_file = base_dir / 'chunks.jsonl'
    queries_file = base_dir / 'queries.draft.json'
    output_file = base_dir / 'evidence-chunk-map.json'

    print('=== P1-B Evidence→Chunk Mapper (v2) ===')
    print(f'chunks: {chunks_file}')
    print(f'queries: {queries_file}')
    print()

    chunks = load_chunks(chunks_file)
    queries_data = load_queries(queries_file)

    print(f'Loaded {len(chunks)} chunks')
    print(f'Loaded {len(queries_data["queries"])} queries')
    print()

    mappings, stats = map_evidence_to_chunks(chunks, queries_data)

    # Build output
    output = {
        'mappingMethod': 'text matching with single-chunk priority',
        'matchingStrategy': {
            'priority': 'single chunk fully contains quote',
            'fallback': 'adjacent chunk pair (only when quote spans boundary)',
            'normalization': 'CRLF→LF, strip trailing whitespace per line',
            'note': 'Evidence quotes are from original corpus; chunks are from cleaned text. Normalization handles line ending and whitespace differences.'
        },
        'statistics': stats,
        'mappings': mappings
    }

    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f'已写入: {output_file}')
    print()
    print('=== 映射统计 ===')
    print(f'总 evidence 项: {stats["totalEvidence"]}')
    print(f'  已映射 (单 chunk): {stats["mappedSingleChunk"]}')
    print(f'  已映射 (跨 chunk): {stats["mappedCrossChunk"]}')
    print(f'  未映射: {stats["unmapped"]}')
    print(f'匹配类型:')
    print(f'  精确匹配: {stats["matchTypeExact"]}')
    print(f'  归一化匹配: {stats["matchTypeNormalized"]}')
    print(f'nearMiss 已映射: {stats["nearMissMapped"]}')

    # List unmapped
    unmapped = [m for m in mappings if m['status'] == 'unmapped']
    if unmapped:
        print()
        print(f'[!] 未映射的 evidence ({len(unmapped)} 条):')
        for m in unmapped:
            print(f'  {m["queryId"]} ev#{m["evidenceIndex"]}: {m["quote"][:60]}...')

if __name__ == '__main__':
    main()
