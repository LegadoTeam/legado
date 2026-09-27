# EPUB hierarchy regression fixtures

- `issue1074-reporter.epub` is the unchanged EPUB inside the reporter's `default.zip`: https://github.com/user-attachments/files/28547763/default.zip . ZIP SHA-256: `267b90916410a01e383a96dfa2164fb79c21df2d33deef13fe5ff34208e14b23`. Its ten NCX entries include three levels and three independent extras.
- `issue1074-containers-fragments.epub` is a generated EPUB 2 fixture containing a resource-less parent, duplicate navigation aliases, two fragments in one resource, and a fragment ID shared by different resources.
- `issue1074-nav3-containers.epub` covers the same content with EPUB 3 navigation and a `span` container. The unique text markers identify incorrect content boundaries.
