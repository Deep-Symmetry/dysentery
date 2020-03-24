#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the DJ Link packet analysis and the devicesql
# database analysis.

DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr npx antora --fetch --generator @djencks/site-generator-default \
  doc/netlify.yml
