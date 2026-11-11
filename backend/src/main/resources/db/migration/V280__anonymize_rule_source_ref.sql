-- V71 seeded source_ref with a wiki page named after a real facility; point it at a generic case-study label.
UPDATE derivation_rule
SET source_ref = replace(source_ref, 'wiki/광명-소각시설-인허가.md', 'wiki/소각시설-인허가-사례.md')
WHERE source_ref LIKE '%광명%';
