/*
 *  Copyright 2021 Cosgy Dev (info@cosgy.dev).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.cosgy.JMusicBot.playlist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.queue.FairQueue;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.cosgy.JMusicBot.util.Cache;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author Kosugi_kun
 */
public class CacheLoader {
    private final BotConfig config;
    Logger log = LoggerFactory.getLogger("CacheLoader");

    public CacheLoader(BotConfig config) {
        this.config = config;
    }

    public void Save(String guildId, FairQueue<QueuedTrack> queue) {
        List<QueuedTrack> list = queue.getList();
        if (list.isEmpty()) {
            return;
        }

        if (!folderExists()) {
            createFolder();
        }
        try {
            createCache(guildId);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            writeCache(guildId, list);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    public void Trim(List<String> list, String str) {
        log.debug("Trimを実行: " + str);
        String s = str.trim();
        if (s.isEmpty()) {
            return;
        }
        list.add(s);
    }

    public List<Cache> GetCache(String serverId) {

        try {
            log.debug("キャッシュの読み込み開始: " + "cache" + File.separator + serverId + ".cash");

            File file = new File(Paths.get("cache" + File.separator + serverId + ".cash").toString());

            byte[] data = new byte[(int) file.length()];
            InputStream reader = new FileInputStream(file);

            reader.read(data);

            ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
            List<dev.cosgy.JMusicBot.util.Cache> deserialized = objectMapper.readValue(data, new TypeReference<List<dev.cosgy.JMusicBot.util.Cache>>() {
            });

            log.debug("キャッシュの読み込み完了");
            return deserialized;
        } catch (IOException e) {
            log.debug("キャッシュの読み込み中にエラーが発生しました。");
            e.printStackTrace();
            return null;
        }
    }

    public CacheResult ConvertCache(List<dev.cosgy.JMusicBot.util.Cache> data) {
        List<String> urls = new ArrayList<>();
        for (dev.cosgy.JMusicBot.util.Cache datum : data) {
            urls.add(datum.getUrl());
        }
        return new CacheResult(urls, false);
    }

    public void createFolder() {
        try {
            Files.createDirectory(Paths.get("cache"));
        } catch (IOException ignore) {
        }
    }

    public boolean folderExists() {
        return Files.exists(Paths.get("cache"));
    }

    public boolean cacheExists(String serverId) {
        log.debug("確認するファイル名：" + serverId + ".json");
        return Files.exists(Paths.get("cache" + File.separator + serverId + ".cash"));
    }

    public void createCache(String serverId) throws IOException {

        if (cacheExists(serverId)) {
            log.info("すでにキャッシュファイルが存在していたため、古いキャッシュを削除します。");
            deleteCache(serverId);
        }
        Files.createFile(Paths.get("cache" + File.separator + serverId + ".cash"));
    }

    public void writeCache(String serverId, List<QueuedTrack> queuedTracks) throws IOException {
        List<dev.cosgy.JMusicBot.util.Cache> data = new ArrayList<>();

        for (QueuedTrack queuedTrack : queuedTracks) {
            AudioTrack que = queuedTrack.getTrack();
            queuedTrack.getTrack().getUserData();
            data.add(new Cache(
                    que.getInfo().title,
                    que.getInfo().author,
                    que.getInfo().length,
                    que.getInfo().identifier,
                    que.getInfo().isStream,
                    que.getInfo().uri,
                    que.getUserData(Long.class)
            ));
        }

        ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
        byte[] bytes = objectMapper.writeValueAsBytes(data);
        FileOutputStream fos = new FileOutputStream(Paths.get("cache" + File.separator + serverId + ".cash").toString(), true);

        fos.write(bytes);
        fos.flush();
        fos.close();
    }

    public CacheLoader deleteCache(String serverId) throws IOException {
        Files.delete(Paths.get("cache" + File.separator + serverId + ".cash"));
        return null;
    }

    public static class CacheLoadError {
        private final int number;
        private final String item;
        private final String reason;

        private CacheLoadError(int number, String item, String reason) {
            this.number = number;
            this.item = item;
            this.reason = reason;
        }

        public int getIndex() {
            return number;
        }

        public String getItem() {
            return item;
        }

        public String getReason() {
            return reason;
        }
    }

    public class CacheResult {
        private final List<String> items;
        private final boolean shuffle;
        private final List<AudioTrack> tracks = new LinkedList<>();
        private final List<CacheLoadError> errors = new LinkedList<>();
        private boolean loaded = false;

        public CacheResult(List<String> items, boolean shuffle) {
            this.items = items;
            this.shuffle = shuffle;
        }

        public void loadTracks(AudioPlayerManager manager, Consumer<AudioTrack> consumer, Runnable callback) {
            if (loaded)
                return;
            loaded = true;
            for (int i = 0; i < items.size(); i++) {
                boolean last = i + 1 == items.size();
                int index = i;
                manager.loadItemOrdered("キャッシュ", items.get(i), new AudioLoadResultHandler() {
                    private void done() {
                        if (last) {
                            if (callback != null)
                                callback.run();
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack at) {
                        if (config.isTooLong(at))
                            errors.add(new CacheLoadError(index, items.get(index), "このトラックは許可された最大長を超えています。"));
                        else {
                            at.setUserData(0L);
                            tracks.add(at);
                            consumer.accept(at);
                        }
                        done();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist ap) {
                        if (ap.isSearchResult()) {
                            trackLoaded(ap.getTracks().get(0));
                        } else if (ap.getSelectedTrack() != null) {
                            trackLoaded(ap.getSelectedTrack());
                        } else {
                            List<AudioTrack> loaded = new ArrayList<>(ap.getTracks());
                            if (shuffle)
                                for (int first = 0; first < loaded.size(); first++) {
                                    int second = (int) (Math.random() * loaded.size());
                                    AudioTrack tmp = loaded.get(first);
                                    loaded.set(first, loaded.get(second));
                                    loaded.set(second, tmp);
                                }
                            loaded.removeIf(config::isTooLong);
                            loaded.forEach(at -> at.setUserData(0L));
                            tracks.addAll(loaded);
                            loaded.forEach(consumer);
                        }
                        done();
                    }

                    @Override
                    public void noMatches() {
                        errors.add(new CacheLoadError(index, items.get(index), "一致するものが見つかりませんでした。"));
                        done();
                    }

                    @Override
                    public void loadFailed(FriendlyException fe) {
                        errors.add(new CacheLoadError(index, items.get(index), "トラックを読み込めませんでした: " + fe.getLocalizedMessage()));
                        done();
                    }
                });
            }
        }

        public List<String> getItems() {
            return items;
        }

        public List<AudioTrack> getTracks() {
            return tracks;
        }

        public List<CacheLoadError> getErrors() {
            return errors;
        }
    }
}
