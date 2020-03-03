#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the DJ Link packet analysis and the devicesql
# database analysis.

cd doc

# Build the unreleased branch of Antora that supports plugins
git clone https://gitlab.com/djencks/antora.git
cd antora
git checkout issue-585-with-377-582-git-credential-plugin
yarn
cd ..

# Build the unreleased Antora LUNR plugin
git clone https://github.com/djencks/antora-lunr.git
cd antora-lunr
git checkout plugin-377
npm install
cd ..

# Build the unreleased generic SVG generator plugin
git clone https://gitlab.com/djencks/asciidoctor-generic-svg-extension.js.git extension
cd extension
git checkout issue-377-plugin
npm install
cd ..

# Build the unreleased byte field generator
git clone https://github.com/Deep-Symmetry/bytefield-svg generator
cd generator
npm install
npm run build
cd ..

# Finally, put them all together to build the documentation site.
cd ..
DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr doc/antora/node_modules/.bin/antora doc/netlify.yml
