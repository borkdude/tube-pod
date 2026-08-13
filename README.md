# tube-pod

Turn YouTube videos into a private podcast. Add a link in the browser. tube-pod
downloads the audio, writes an RSS feed, and serves both. Subscribe to the feed
in Apple Podcasts or any other podcast player.

![The tube-pod admin panel](doc/screenshot.jpg)

The admin panel uses [buzz](https://github.com/borkdude/buzz).

## Requirements

tube-pod calls `yt-dlp` and `ffprobe`, so install both first.

    brew install yt-dlp ffmpeg

## Run

    bb admin    # the panel and the podcast, on port 8088
    bb dev      # the same, plus an nrepl on 1667

Open http://localhost:8088 for the panel. The feed is at `/feed.xml` and the
audio is under `/audio`.

Set `TUBE_POD_URL` to the address that your podcast player can reach. The feed
puts this address in each episode link. The default is `http://10.0.1.11:8088`.

    TUBE_POD_URL=http://192.168.1.20:8088 bb admin

## Push to a server

A laptop is asleep when you want to listen, so the files belong on a machine
that stays on. Set `TUBE_POD_REMOTE` to an rsync destination. After each change
tube-pod rsyncs `audio/` and `feed.xml` there, in the background, and the panel
shows the result next to the episode count.

    TUBE_POD_URL=https://example.com/mypodcast \
    TUBE_POD_REMOTE=me@example.com:/srv/tube-pod \
    bb admin

`TUBE_POD_URL` is then the address of that server, because the podcast player
gets the audio from there. The panel and `yt-dlp` stay on the laptop.

The server needs no tube-pod and no Clojure. It serves two things as static
files: `feed.xml` and `audio/`.

Run `yt-dlp` on the laptop and not on the server. YouTube answers a datacenter
address with "Sign in to confirm you're not a bot", so a download from a virtual
server fails.

An unguessable path is what keeps the feed private. Anybody with the address can
read it, and search engines find a simple one.

## What it does

Paste a YouTube link and press Enter. tube-pod runs `yt-dlp`, and the panel
shows the progress of the download. When it is complete, the episode appears in
the list and in the feed.

Press the play button to listen in the panel. This is browser state, so it
starts at once and it survives a restart of the server.

Press the cross to delete an episode. tube-pod removes the audio file and writes
the feed again. With a remote, the delete goes there too.

Each file goes in `audio/`, with the video id as its name. `feed.xml` lists the
files by modification time, newest first. `feed.clj` writes and serves the feed
without the panel.
