#!/usr/bin/env python3
"""Rebuild only reviewed manufacturer-owned integrations. Never fetch aggregator catalogues."""
import json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ALLOWED={'openai':'openai','anthropic':'anthropic','gemini':'google','deepseek':'deepseek','qwen':'alibaba','moonshot':'moonshotai','xai':'x-ai'}
data=json.loads((ROOT/'research/official-models.json').read_text())
assert {p['id'] for p in data['providers']}==set(ALLOWED)
assert len({m['uid'] for m in data['models']})==len(data['models'])
for m in data['models']:
 assert m['provider'] in ALLOWED and m['vendor']==ALLOWED[m['provider']]
 assert m['description'] and m['source'].startswith('https://') and m['requiresKey']
 assert m['execution']=='integrated'
encoded=json.dumps(data,ensure_ascii=False,separators=(',',':'))
assert not re.search('openrouter|pollinations|siliconflow|groq',encoded,re.I)
(ROOT/'src/main/resources/catalog.json').write_text(encoded)
print(f"Official catalog: {len(data['models'])} integrations, {len(ALLOWED)} manufacturers")
