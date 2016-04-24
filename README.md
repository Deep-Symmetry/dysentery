# dysentery
Exploring ways to participate in a Pioneer Pro DJ Link network.

[![License](https://img.shields.io/badge/License-Eclipse%20Public%20License%201.0-blue.svg)](#license)

### Status

This is in no way a sanctioned implementation of the protocols. It
should be obvious, but:

> Use at your own risk!

While these techniques appear to work for us so far, there are many
gaps in our knowledge, and things could change at any time with new
releases of hardware or even firmware updates from Pioneer.

That said, if you find anything wrong, or discover anything new,
*please* open an issue or submit a pull request so we can all improve
our understanding together.

### History

This research began in the summer of 2015 as I was trying to figure
out a reliable way to synchronize
[Afterglow](https://github.com/brunchboy/afterglow#afterglow) light
shows with performances on my CDJs. I broke out
[Wireshark](https://www.wireshark.org) and after staring at packet
captures over a weekend, I was able to identify how to track the
current BPM and beat locations by passively watching broadcast
traffic, which was my main goal. I still could not get a lock on where
the down beat fell, because I could not tell which player was the
Master.

In the spring of 2016 I saw a posting on the original
[VJ Forums thread](http://vjforums.info/threads/cdj-2000-ethernet-protocol-for-live-bpm-sync.39265/page-2#post-295258)
where we had been discussing this, announcing that
[Diogo Santos](mailto:diogommsantos@gmail.com) had made an important
breakthrough. By broadcasting packets that pretended to be a CDJ, his
software was able to get the other players to start sending it more
details, including information I had not been able to find in other
ways. He was kind enough to share his code, and that was the impetus
behind starting this project, to consolidate what people are learning
about this protocol, and make it available for other projects to
benefit from.

### Why Dysentery?

The name of this project is a reference to one of the infamous hazards faced in
[The Oregon Trail](https://en.wikipedia.org/wiki/The_Oregon_Trail_%28video_game%29),
a game which helped many students in the eighties and nineties understand what life
was like for pioneers exploring the American West. Since we are exploring the
protocol used by Pioneer gear, it seemed at least slightly appropriate. And, ok, I
have a hard time resisting forced puns. Let's hope none of us see:

[![You have died of dysentery](http://www.strangeloopgames.com/wp-content/uploads/2014/01/Dysentary.png)](http://www.strangeloopgames.com/you-have-died-of-dysentery-how-games-will-revolutionize-education/)

## License

<img align="right" alt="Deep Symmetry" src="doc/assets/DS-logo-bw-200-padded-left.png">
Copyright © 2016 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software. A copy of the license
can be found in
[LICENSE](https://rawgit.com/brunchboy/dysentery/master/LICENSE)
within this project.
