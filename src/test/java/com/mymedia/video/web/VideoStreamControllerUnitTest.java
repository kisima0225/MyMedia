package com.mymedia.video.web;

import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoStreamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoStreamControllerUnitTest {

    private static final Long USER_ID = 7L;
    private static final Long FILE_ID = 11L;
    private static final String CONTENT = "0123456789ABCDEFGHIJ";
    private static final Instant LAST_MODIFIED = Instant.parse("2026-08-18T10:15:30Z");

    @TempDir
    Path root;

    @Test
    void fullResponseContainsRepresentationHeadersAndBody() throws Exception {
        VideoStreamService.StreamTarget target = target();
        VideoStreamController controller = controller(target);

        var response = controller.stream(principal(), FILE_ID, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getETag()).isEqualTo(target.etag());
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("video/x-matroska");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(CONTENT.length());
        assertThat(bodyOf(response.getBody())).isEqualTo(CONTENT);
    }

    @Test
    void partialResponseUsesResolvedRangeAndBody() throws Exception {
        VideoStreamController controller = controller(target());

        var response = controller.stream(principal(), FILE_ID, "bytes=5-9", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 5-9/20");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(5L);
        assertThat(bodyOf(response.getBody())).isEqualTo("56789");
    }

    @Test
    void staleIfRangeFallsBackToFullResponse() throws Exception {
        VideoStreamController controller = controller(target());

        var response = controller.stream(principal(), FILE_ID, "bytes=0-4", "\"stale\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(CONTENT.length());
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
        assertThat(bodyOf(response.getBody())).isEqualTo(CONTENT);
    }

    @Test
    void ifRangeDateOnlyMatchesWhenRepresentationIsNotNewer() throws Exception {
        VideoStreamController controller = controller(target());
        String matchingDate = "Tue, 18 Aug 2026 10:15:30 GMT";
        String staleDate = "Tue, 18 Aug 2026 10:15:29 GMT";

        var matching = controller.stream(principal(), FILE_ID, "bytes=0-4", matchingDate);
        var stale = controller.stream(principal(), FILE_ID, "bytes=0-4", staleDate);

        assertThat(matching.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(bodyOf(matching.getBody())).isEqualTo("01234");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyOf(stale.getBody())).isEqualTo(CONTENT);
    }

    @Test
    void unsatisfiableResponseIncludesRangeMetadata() throws Exception {
        VideoStreamController controller = controller(target());

        var response = controller.stream(principal(), FILE_ID, "bytes=99-100", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */20");
        assertThat(response.getHeaders().getContentLength()).isZero();
        assertThat(response.getBody()).isNull();
    }

    @Test
    void zeroTransferFallsBackToDirectBufferUntilRangeIsWritten() throws Exception {
        VideoStreamService.StreamTarget target = target();
        VideoStreamController controller = controller(target);
        FileChannel channel = mock(FileChannel.class);
        byte[] source = CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(channel.transferTo(anyLong(), anyLong(), any())).thenReturn(0L);
        when(channel.read(any(ByteBuffer.class), anyLong())).thenAnswer(invocation -> {
            ByteBuffer destination = invocation.getArgument(0);
            long position = invocation.getArgument(1);
            int sourcePosition = Math.toIntExact(position);
            int length = Math.min(destination.remaining(), source.length - sourcePosition);
            destination.put(source, sourcePosition, length);
            return length;
        });

        try (MockedStatic<FileChannel> fileChannelOpen = mockStatic(FileChannel.class)) {
            fileChannelOpen.when(() -> FileChannel.open(target.path(), StandardOpenOption.READ))
                    .thenReturn(channel);

            var response = controller.stream(principal(), FILE_ID, "bytes=3-10", null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);

            assertThat(output.toByteArray()).isEqualTo(Arrays.copyOfRange(source, 3, 11));
        }

        verify(channel, atLeast(2)).transferTo(anyLong(), anyLong(), any());
        verify(channel, atLeast(1)).read(any(ByteBuffer.class), anyLong());
    }

    @Test
    void streamingIoFailureIsPropagated() throws Exception {
        VideoStreamService.StreamTarget target = target();
        VideoStreamController controller = controller(target);
        FileChannel channel = mock(FileChannel.class);
        IOException failure = new IOException("disk read failed");
        when(channel.transferTo(anyLong(), anyLong(), any())).thenThrow(failure);

        try (MockedStatic<FileChannel> fileChannelOpen = mockStatic(FileChannel.class)) {
            fileChannelOpen.when(() -> FileChannel.open(target.path(), StandardOpenOption.READ))
                    .thenReturn(channel);

            var response = controller.stream(principal(), FILE_ID, null, null);

            assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                    .isSameAs(failure);
        }
    }

    private VideoStreamController controller(VideoStreamService.StreamTarget target) {
        VideoStreamService streamService = mock(VideoStreamService.class);
        UserQueryService userQueryService = mock(UserQueryService.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(USER_ID);
        when(userQueryService.findByUsername("user"))
                .thenReturn(Optional.of(account));
        when(streamService.locate(USER_ID, FILE_ID)).thenReturn(target);
        return new VideoStreamController(streamService, userQueryService);
    }

    private VideoStreamService.StreamTarget target() throws Exception {
        Path file = root.resolve("movie.mkv");
        Files.writeString(file, CONTENT);
        return new VideoStreamService.StreamTarget(
                file, CONTENT.length(), "\"etag\"", LAST_MODIFIED, "video/x-matroska");
    }

    private static UserDetails principal() {
        return User.withUsername("user").password("pw").roles("USER").build();
    }

    private static String bodyOf(StreamingResponseBody body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
