package com.github.catvod.spider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;

import static com.github.catvod.spider.TestSupport.first;
import static com.github.catvod.spider.TestSupport.object;
import static com.github.catvod.spider.TestSupport.string;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LocalTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void detailContentExcludesDirectoriesNamedAsMedia() throws Exception {
        File video = folder.newFile("video.mp4");
        folder.newFolder("avi");
        folder.newFolder("fake.mkv");

        JsonObject detail = object(new Local().detailContent(Collections.singletonList(video.getAbsolutePath())));
        String playUrl = string(first(detail, "list"), "vod_play_url");

        assertEquals("video.mp4$" + video.getAbsolutePath(), playUrl);
    }

    @Test
    public void playerContentExcludesDirectoriesNamedAsSubtitles() throws Exception {
        File video = folder.newFile("video.mp4");
        folder.newFile("subtitle.srt");
        folder.newFolder("ass");
        folder.newFolder("fake.srt");

        JsonObject result = object(new Local().playerContent("播放", video.getAbsolutePath(), Collections.emptyList()));
        JsonArray subtitles = result.getAsJsonArray("subs");

        assertEquals(1, subtitles.size());
        assertEquals("subtitle", subtitles.get(0).getAsJsonObject().get("name").getAsString());
    }
}
