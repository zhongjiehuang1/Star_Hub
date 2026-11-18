#!/usr/bin/env python3
"""Build the bounded local city index from the official GeoNames cities500.zip.

Usage: python3 scripts/build-geonames-index.py cities500.zip INDEX.tsv.gz RECORDS.tsv.gz
Requires standard Unix sort and gzip. Record the input SHA-256 and update
CityLookup.INDEX_SIZE and the resource README with the printed byte count.
"""
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import zipfile

if len(sys.argv) != 4:
    sys.exit(__doc__)
source, destination, records_destination = sys.argv[1:]
normalize = lambda s: unicodedata.normalize('NFKC', s).strip().lower()
with tempfile.TemporaryDirectory(prefix='starhub-geonames-') as temp:
    unsorted = os.path.join(temp, 'unsorted.tsv')
    sorted_file = os.path.join(temp, 'sorted.tsv')
    records_file = os.path.join(temp, 'records.tsv')
    with zipfile.ZipFile(source) as archive, archive.open('cities500.txt') as raw, open(unsorted, 'w', encoding='utf-8') as output, open(records_file, 'wb') as records:
        for line in raw:
            fields = line.decode('utf-8').rstrip('\n').split('\t')
            if len(fields) < 19:
                continue
            ident, name, ascii_name, aliases, lat, lon, _, _, country, _, admin, _, _, _, population = fields[:15]
            try:
                float(lat); float(lon); int(population or 0); int(ident)
            except ValueError:
                continue
            if not re.fullmatch('[A-Z]{2}', country):
                continue
            names = set()
            for alias in [name, ascii_name, *aliases.split(',')]:
                alias = normalize(alias)
                if len(alias) < 2 or len(alias) > 100 or not all(('a' <= c <= 'z') or ('\u4e00' <= c <= '\u9fff') or c in " '-." for c in alias):
                    continue
                names.add(alias)
            record = '\t'.join([ident, name, lat, lon, country, admin]) + '\n'
            offset = records.tell()
            records.write(record.encode('utf-8'))
            for alias in names:
                output.write('\t'.join([alias, str(offset), population or '0']) + '\n')
    with open(sorted_file, 'wb') as output:
        subprocess.run(['sort', '-t', '\t', '-k1,1', '-k3,3nr', unsorted], stdout=output,
                       env={**os.environ, 'LC_ALL': 'C'}, check=True)
    compact_file = os.path.join(temp, 'compact.tsv')
    with open(sorted_file, 'rb') as source_file, open(compact_file, 'wb') as compact:
        for line in source_file:
            compact.write(line.rsplit(b'\t', 1)[0] + b'\n')
    with open(records_destination, 'wb') as output:
        subprocess.run(['gzip', '-n', '-9', '-c', records_file], stdout=output, check=True)
    with open(destination, 'wb') as output:
        subprocess.run(['gzip', '-n', '-9', '-c', compact_file], stdout=output, check=True)
    print('Uncompressed index bytes:', os.path.getsize(compact_file))
    print('Uncompressed records bytes:', os.path.getsize(records_file))
