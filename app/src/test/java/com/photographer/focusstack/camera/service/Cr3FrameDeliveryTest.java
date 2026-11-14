package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.controller.JobController;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.stream.FileImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Cr3FrameDeliveryTest {

    @Test
    void capturedCr3ServesTheCameraPreviewAndExportsTheOriginalFile(@TempDir Path storage)
            throws Exception {
        Path imageioModule = Path.of("canon-cr3-imageio");
        if (!Files.isDirectory(imageioModule)) {
            imageioModule = Path.of("..").resolve(imageioModule);
        }
        Path sample = imageioModule.resolve("src/test/resources/samples/sample.cr3");
        byte[] original = Files.readAllBytes(sample);
        byte[] embeddedPreview;
        try (var input = new FileImageInputStream(sample.toFile())) {
            var previewBox = Cr3Parser.parse(input).prvwBox();
            assertNotNull(previewBox, "The G5 X Mark II capture contains the camera preview in PRVW");
            embeddedPreview = previewBox.jpegData();
        }

        var reference = new CameraMediaReference("card1", "/ccapi/ver140/contents/card1/IMG_0001.CR3",
                "IMG_0001.CR3", "CR3", "ver140");
        var camera = mock(CameraMediaProvider.class);
        when(camera.downloadMedia(eq(reference), anyLong(), anyInt())).thenAnswer(call -> {
            int offset = Math.toIntExact(call.getArgument(1, Long.class));
            int end = Math.min(original.length, offset + call.getArgument(2, Integer.class));
            return new CameraMediaProvider.MediaChunk(reference, offset,
                    Arrays.copyOfRange(original, offset, end), (long) original.length, end == original.length);
        });
        var frames = new FrameDataStore(storage.toString(), 1024);
        String frameId = NativeFrameFileIngestor.ingest(frames, camera, reference);

        var jobs = mock(StackingJobService.class);
        var job = new StackingJob("capture", "camera", JobStatus.COMPLETED, null, null, null,
                List.of(frameId), null);
        when(jobs.getJob(job.id())).thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs, mock(SearchService.class),
                frames, new RawImagePreviewService())).build();

        mvc.perform(get("/api/v1/jobs/{id}/frames/{frame}/image", job.id(), frameId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(embeddedPreview));

        var download = mvc.perform(get("/api/v1/jobs/{id}/export", job.id()))
                .andExpect(request().asyncStarted()).andReturn();
        var zipResponse = mvc.perform(asyncDispatch(download))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn().getResponse();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipResponse.getContentAsByteArray()))) {
            var entry = zip.getNextEntry();
            assertNotNull(entry);
            assertEquals("frame_001_IMG_0001.CR3", entry.getName());
            assertArrayEquals(original, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }
}
