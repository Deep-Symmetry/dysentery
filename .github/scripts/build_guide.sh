#!/usr/bin/env bash

set -e  # Exit if any command fails.

# There is no point in doing this if we lack the SSH key to publish the guide.
if [ "$GUIDE_SSH_KEY" != "" ]; then

    # Set up node dependencies
    npm install

    # Build the documentation site.
    npx antora --fetch doc/github-actions.yml

    # Make sure there are no broken links in the versions we care about.
    curl https://htmltest.wjdp.uk | bash
    bin/htmltest

    # Publish it to the right place on the Deep Symmetry web server.
    rsync -avz doc/build/site/ guides@deepsymmetry.org:/var/www/guides/djl-analysis/

else
    echo "No SSH key present, not building protocol analysis."
fi
