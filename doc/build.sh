#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the DJ Link packet analysis and the devicesql
# database analysis.

npx antora --fetch doc/netlify.yml
