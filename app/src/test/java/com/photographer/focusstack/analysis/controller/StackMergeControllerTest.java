package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.analysis.service.ImageJStackMerger;
import com.photographer.focusstack.analysis.service.RawDecodeBudget;
import com.photographer.focusstack.analysis.service.StackFileProcessor;
import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.service.StackingJobService;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.StackingJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StackMergeControllerTest {

    @TempDir
    Path directory;

    @Test
    void buildsAndCachesAStoredStackWithAMissingLegacyFrame() throws Exception {
        var jobs = mock(StackingJobService.class);
        var store = new FrameDataStore(directory.toString(), 4096);
        byte[] pixels;
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(13, 11, BufferedImage.TYPE_INT_RGB), "png", bytes);
            pixels = bytes.toByteArray();
        }
        store.put("first", "first.png", pixels);
        store.put("third", "third.png", pixels);
        when(jobs.getJob("job")).thenReturn(new StackingJob("job", "camera", JobStatus.COMPLETED,
                null, null, null, List.of("first", "missing", "third"), null));
        var files = new StackFileProcessor(new RawDecodeBudget(1, 64L << 20));
        var mvc = MockMvcBuilders.standaloneSetup(new StackMergeController(new ImageJStackMerger(), files, jobs, store))
                .build();

        var output = mvc.perform(post(ApiPaths.V1 + "/analysis/merge-stored-stack/job"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Photographer-Stack-Source", "RASTER"))
                .andExpect(header().string("X-Photographer-Stack-Frame-Count", "2"))
                .andReturn().getResponse().getContentAsByteArray();

        mvc.perform(get(ApiPaths.V1 + "/analysis/merge-result/job"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(output));
    }
}
