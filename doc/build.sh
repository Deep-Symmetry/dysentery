#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the DJ Link packet analysis and the devicesql
# database analysis.

npm install yarn
npm install asciidoctor-mathjax
cd doc

# Build the unreleased branch of Antora that supports plugins
if [ ! -d "antora" ]
then
    git clone https://gitlab.com/djencks/antora.git
    cd antora
    git checkout issue-585-with-377-582-git-credential-plugin
    ../../node_modules/.bin/yarn
    cd ..
fi

# Build the unreleased Antora LUNR plugin
if [ ! -d "antora-lunr" ]
then
    git clone https://github.com/djencks/antora-lunr.git
    cd antora-lunr
    git checkout plugin-377
    npm install
    cd ..
fi

# Build the unreleased generic SVG generator plugin
if [ ! -d "extension" ]
then
    git clone https://gitlab.com/djencks/asciidoctor-generic-svg-extension.js.git extension
    cd extension
    git checkout issue-377-plugin
    npm install
    cd ..
fi

# Build the unreleased byte field generator
if [ ! -d "generator" ]
then
    export PATH="$PATH:$PWD/clojure/bin"
    git clone https://github.com/Deep-Symmetry/bytefield-svg generator
    cd generator
    npm install
    npm run build
    cd ..
fi


# Finally, put them all together to build the documentation site.
cd ..
DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr doc/antora/node_modules/.bin/antora doc/netlify.yml
