-- V71 seeded source_ref with a wiki page named after a real facility; point it at a generic case-study label.
-- The pattern avoids repeating that name here.
UPDATE derivation_rule
SET source_ref = regexp_replace(source_ref, '^wiki/[^,]*-소각시설-인허가\.md', 'wiki/소각시설-인허가-사례.md')
WHERE source_ref ~ '^wiki/[^,]*-소각시설-인허가\.md';
