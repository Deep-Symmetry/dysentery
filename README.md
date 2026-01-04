# dysentery
Exploring ways to participate in a Pioneer Pro DJ Link network.

[![License](https://img.shields.io/github/license/Deep-Symmetry/dysentery?color=blue)](#license)
[![project chat](https://img.shields.io/badge/chat-on%20zulip-brightgreen)](https://deep-symmetry.zulipchat.com/#narrow/stream/275855-dysentery-.26-crate-digger)

## Quick Start

To watch and analyze the packets being sent between your Pioneer gear,
download and run the latest `dysentery.jar` file from the
[releases](https://github.com/brunchboy/dysentery/releases) page. You
will need a
[Java runtime environment](https://java.com/inc/BrowserRedirect1.jsp)
Once you have a recent one installed, you can probably run dysentery
by just double-clicking the jar file. See the [Status](#status)
section for more details, explanation, and a screen shot.

> :wrench: If you&rsquo;re looking for a library to use in your own
> projects, that&rsquo;s what
> [beat-link](https://github.com/brunchboy/beat-link#beat-link) was
> developed for, and
> [@EvanPurkhiser](https://github.com/EvanPurkhiser) is now also
> developing [prolink-connect](https://github.com/EvanPurkhiser/prolink-connect)
> if you&rsquo;d like a TypeScript version.
>
> :star2: And if you want to synchronize shows without having to
> write your own software, check out
> [beat-link-trigger](https://github.com/brunchboy/beat-link-trigger#beat-link-trigger).

## Disclaimer

This is in no way a sanctioned implementation of the protocols. It
should be obvious, but:

> :warning: Use at your own risk! For example, there are reports that
> the XDJ-RX crashes when dysentery starts, so don&rsquo;t use it with one
> on your network. As Pioneer themselves
> [explain](https://forums.pioneerdj.com/hc/en-us/community/posts/203113059-xdj-rx-as-single-deck-on-pro-dj-link-),
> the XDJ-RX does not actually implement the protocol:
>
> &ldquo;The LINK on the RX is ONLY for linking to rekordbox on your
> computer or a router with WiFi to connect rekordbox mobile. It can
> not exchange LINK data with other CDJs or DJMs.&rdquo;

While these techniques appear to work for us so far, there are many
gaps in our knowledge, and things could change at any time with new
releases of hardware or even firmware updates from Pioneer.

That said, if you find anything wrong, or discover anything new,
*please* [open an
Issue](https://github.com/brunchboy/dysentery/issues), contact us on
the [Zulip
stream](https://deep-symmetry.zulipchat.com/#narrow/stream/275855-dysentery-.26-crate-digger)
or submit a pull request so we can all improve our understanding
together.

## Analysis

A major goal of this project is the [Packet
Analysis](https://djl-analysis.deepsymmetry.org/), which is intended
to be useful to anyone who wants to write code to interact with DJ
Link networks. Check out what we have learned so far, and please help
us figure out more if you can!

The packet captures used to create that document can be downloaded
([Sections 1 and 2](doc/assets/powerup.pcapng),
[Sections 3 and 4](doc/assets/to-virtual.pcapng)) so you can see if
you notice anything we have not, even if you don&rsquo;t have any
Pioneer gear to try out.

### Funding

Dysentery and its research products are, and will remain, completely
free and open-source. If they have helped you, taught you something,
or inspired you, please let us know and share some of your discoveries
and code. If you&rsquo;d like to financially support this ongoing research,
you are welcome (but by no means obligated) to donate to offset the
hundreds of hours of research, development, and writing that have
already been invested. Or perhaps to facilitate future efforts, tools,
toys, and time to explore.

<a href="https://liberapay.com/deep-symmetry/donate"><img style="vertical-align:middle" alt="Donate using Liberapay"
    src="https://liberapay.com/assets/widgets/donate.svg"></a> using Liberapay, or
<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=M7EXPEX7CZN8Q"><img
    style="vertical-align:middle" alt="Donate"
    src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif"></a> using PayPal

> If enough people jump on board, we may even be able to get a newer
> CDJ to experiment with, although that&rsquo;s an unlikely stretch goal.
> :grinning:

## Getting Help

<a href="http://zulip.com"><img align="right" alt="Zulip logo"
 src="doc/assets/zulip-icon-circle.svg" width="128" height="128"></a>

Deep Symmetry&rsquo;s projects are generously sponsored with hosting
by <a href="https://zulip.com">Zulip</a>, an open-source modern team
chat app designed to keep both live and asynchronous conversations
organized. Thanks to them, you can <a
href="https://deep-symmetry.zulipchat.com/#narrow/stream/275855-dysentery-.26-crate-digger">chat
with our community</a>, ask questions, get inspiration, and share your
own ideas.

## Status

Dysentery is currently being developed as a
[Clojure](http://clojure.org/) library, because I find that to be the
most powerful development environment available to me at the moment.
Once I figure things out well enough here, I implement them in
[beat-link](https://github.com/brunchboy/beat-link#beat-link), which
is intended to be useful in other projects: it is a standard Java
library available as a package from Maven Central. If you want to hack
on the dysentery source, you&rsquo;ll need to learn a little bit about
Clojure. Finally,
[beat-link-trigger](https://github.com/brunchboy/beat-link-trigger#beat-link-trigger)
builds a friendly graphical interface on top of beat-link, making it
easy to synchronize light shows, videos, and Ableton Live to tracks
played on CDJs.

> As mentioned above, other people are implementing projects with the
> help of this research (and in many cases, contributing to the research
> itself). Nice examples include:
>
> * [Cardinia Mini](https://nudge.id.au/cardinia-mini/index.html)
> * [Prolink Tools](https://prolink.tools)
> * [prolink-go](https://github.com/EvanPurkhiser/prolink-go)
> * [python-prodj-link](https://github.com/flesniak/python-prodj-link)
> * [prolink-cpp](https://github.com/grantHarris/prolink-cpp)

You can run dysentery and look at what it finds on your network by
just downloading and executing the jar, though, and we hope you will,
to help us gather more information!

It is already able to watch for DJ Link traffic on all your network
interfaces, and tell you what devices have been noticed, and the local
and broadcast addresses you will want to use when creating a virtual
CDJ device to participate in that network.

Here is an example of trying that out by running Dysentery as an
executable jar on my network at home:

```
> java -jar dysentery.jar
Looking for DJ Link devices...
Found:
   CDJ-2000nexus /172.16.42.4
   DJM-2000nexus /172.16.42.5
   CDJ-2000nexus /172.16.42.6

To communicate create a virtual CDJ with address /172.16.42.2,
MAC address 3c:15:c2:e7:08:6c, and use broadcast address /172.16.42.255

Close any player window to exit.
```

It also creates a virtual CDJ to ask those devices to send status
updates, and opens windows tracking the packets it receives from them.
When a packet changes the value of one of the bytes displayed, the
background of that byte is drawn in blue, which gradually fades back
to black when the value is not changing. This helps to identify what
parts of the packet change when you do something on the device being
analyzed.

To further focus analysis, if a byte has a value that we expect, it is
colored green; if it has an unexpected value, it is colored red. Bytes
that we don&rsquo;t yet understand are colored white. If you see any
white values changing, that is a puzzle that remains to be
solved&mdash;see if you can identify any pattern, or figure out what
they might convey. If you do, or if any byte value shows up in red,
please [open an Issue](https://github.com/brunchboy/dysentery/issues)
to let us know. Bytes which are expected to contain the device name
and firmware version are rendered as text rather than hex, to make
them more readable.

<img src="doc/assets/PacketWindow.png" width="520" alt="Packet Window">

Underneath the raw byte values there is a timestamp which shows when
the most recent packet was received. As with the byte values, its
background will flash blue when the timestamp changes, and fade to
black over the next second, until the next packet is received.

Beneath the timestamp is a an interpretation of the meaning of the
packet, as best we can currently understand it, with italic field
labels corresponding to the byte fields identified in the
[beats](https://djl-analysis.deepsymmetry.org/djl-analysis/beats.html)
and
[status](https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html)
sections of the [Packet
Analysis](https://djl-analysis.deepsymmetry.org/).

> If you have access to any Pioneer Nexus gear, please run Dysentery
> and see if the results it gives seem to make sense for your
> equipment. So far it has only been tested with a pair of CDJ-2000
> nexus players and a DJM-2000 nexus mixer. Even better, if you can
> help us figure out more of the meanings of the packets, or identify
> things that we don&rsquo;t yet have right, and thereby improve the
> analysis for everyone, please
> [open an Issue](https://github.com/brunchboy/dysentery/issues)!

To try this, download the latest `dysentery.jar` from the
[releases](https://github.com/brunchboy/dysentery/releases) page, make
sure you have a recent Java environment installed, and run it as shown
above.

To build it yourself, and play with it interactively, you will need to
clone this repository and install [Leiningen](http://leiningen.org).
Then, within the directory into which you cloned the repo, you can
type `lein repl` to enter a Clojure Read-Eval-Print-Loop with the
project loaded:

```
> lein repl
nREPL server started on port 53806 on host 127.0.0.1 - nrepl://127.0.0.1:53806
REPL-y 0.3.7, nREPL 0.2.12
Clojure 1.8.0
Java HotSpot(TM) 64-Bit Server VM 1.8.0_77-b03
dysentery loaded.
dysentery.core=>
```

At that point, you can evaluate Clojure expressions:

```clojure
(view/find-devices)
;; => Looking for DJ Link devices...
;; => Found:
;; =>   CDJ-2000nexus /172.16.42.5
;; =>   DJM-2000nexus /172.16.42.3
;; =>   CDJ-2000nexus /172.16.42.4
;; =>
;; => To communicate create a virtual CDJ with address /172.16.42.2,
;; => MAC address 3c:15:c2:e7:08:6b, and use broadcast address /172.16.42.255
nil
```

To log details about beat packets from a particular player (this was
built to help get the details of Beat Link&rsquo;s `BeatSender`
implementation correct), bring up the device windows using
`(view/find-devices)` from the REPL as shown above, then evaluate an
expression like:

```clojure
(view/log-beats 3 "/Users/james/Desktop/beats.txt")
```

> This causes all beats from the player 3 to be logged to the
> specified file, producing output like this:

```
Starting beat log for device 3 at Sat Sep 01 15:17:23 CDT 2018

Beat at   0.444, skew:   n/a, B_b: 2 [1 @status +117, beat:  285], BPM: 129.0, pitch: +0.00%
Beat at   0.910, skew:   1ms, B_b: 3 [2 @status + 76, beat:  286], BPM: 129.0, pitch: +0.00%
Beat at   1.375, skew:   0ms, B_b: 4 [3 @status + 53, beat:  287], BPM: 129.0, pitch: +0.00%
Beat at   1.841, skew:   0ms, B_b: 1 [4 @status + 55, beat:  288], BPM: 129.0, pitch: +0.00%
```

To stop the beat logger (without having to exit dysentery):

```clojure
(view/log-beats)
```

To build the executable jar:

```
> lein uberjar
Compiling dysentery.core
Compiling dysentery.finder
Compiling dysentery.util
Compiling dysentery.vcdj
Compiling dysentery.view
Created /Users/james/git/dysentery/target/dysentery-0.1.0-SNAPSHOT.jar
Created /Users/james/git/dysentery/target/dysentery.jar
```

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

#### Virtual CDJ

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

#### Initial metadata breakthrough

In December 2016 I heard from
[@EvanPurkhiser](https://github.com/EvanPurkhiser), who had found this
project, and went on to make important breakthroughs in obtaining track
metadata.

#### Robust metadata understanding

In May 2017 [Austin Wright](https://bitbucket.org/awwright/) contacted
me on the (retired) Afterglow
[Gitter channel](https://gitter.im/brunchboy/afterglow) and told me
about some really cool work he was doing. He was even gracious enough
to publish a bunch of
[source code](https://bitbucket.org/awwright/libpdjl) that I&rsquo;ve been
able to use to get a much deeper understanding of how metadata queries
work, and to gain access to things like beat grid information (and
eventually track waveform images). This is the current area of active
research.

#### Sync control and tempo mastery

In the summer of 2018 I dug into implementing more of the protocol, so
that the virtual CDJ could send its own status updates and beat
packets, become tempo master and control the tempo and beat grid, as
well as telling other devices to turn sync on or off, or become tempo
master. Also figured out how to respond correctly when the nexus mixer
told the virtual CDJ to do those things.

#### nxs2 and beyond

Throughout the succeeding years we continued to expand our knowledge
and ability to use more elements of the protocols and data files,
including new nxs2 features like colored and named cues, phrase
analysis (thanks to [Michael Ganss](https://github.com/mganss)), and
towards the end of 2020 we figured out how to support new CDJ-3000
features, including supporting six channels and exciting new packets
with very valuable information contributed by [David
Ng](https://github.com/nudge).

### Why Dysentery?

The name of this project is a reference to one of the infamous hazards faced in
[The Oregon Trail](https://en.wikipedia.org/wiki/The_Oregon_Trail_%28video_game%29),
a game which helped many students in the eighties and nineties understand what life
was like for pioneers exploring the American West. Since we are exploring the
protocol used by Pioneer gear, it seemed at least slightly appropriate. And, ok, I
have a hard time resisting forced puns. Let&rsquo;s hope none of us see:

![You have died of dysentery](doc/assets/died-of-dysentery.jpg)

## License

<a href="http://deepsymmetry.org"><img align="right" alt="Deep Symmetry"
 src="doc/assets/DS-logo-github.png" width="250" height="150"></a>

Copyright © 2016–2023 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software. A copy of the license
can be found in
[LICENSE](https://rawgit.com/brunchboy/dysentery/master/LICENSE)
within this project.
