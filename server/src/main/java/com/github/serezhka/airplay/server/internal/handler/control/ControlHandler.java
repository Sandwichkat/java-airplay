package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ControlHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest request)) {
            log.error("Unknown control request type: {}", msg);
            return;
        }

        var session = resolveSession(request);
        if (RtspVersions.RTSP_1_0.equals(request.protocolVersion())) {
            var response = createRtspResponse(request);
            if (HttpMethod.GET.equals(request.method()) && "/info".equals(request.uri())) {
                handleGetInfo(response);
            } else if (HttpMethod.POST.equals(request.method()) && "/pair-setup".equals(request.uri())) {
                handlePairSetup(session, response);
            } else if (HttpMethod.POST.equals(request.method()) && "/pair-verify".equals(request.uri())) {
                handlePairVerify(session, request, response);
            } else if (HttpMethod.POST.equals(request.method()) && "/fp-setup".equals(request.uri())) {
                handleFairPlaySetup(session, request, response);
            } else if (RtspMethods.SETUP.equals(request.method())) {
                handleRtspSetup(ctx, session, request, response);
            } else if (HttpMethod.POST.equals(request.method()) && "/feedback".equals(request.uri())) {
                // heartbeat
            } else if (RtspMethods.GET_PARAMETER.equals(request.method())) {
                handleRtspGetParameter(response);
            } else if (RtspMethods.RECORD.equals(request.method())) {
                handleRtspRecord(response);
            } else if (RtspMethods.SET_PARAMETER.equals(request.method())) {
                handleRtspSetParameter(response);
            } else if ("FLUSH".equals(request.method().toString())) {
                // stream end
            } else if (RtspMethods.TEARDOWN.equals(request.method())) {
                handleRtspTeardown(session, request);
            } else if (HttpMethod.POST.equals(request.method()) && request.uri().equals("/audioMode")) {
                // audio mode default
            } else {
                log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
            }
            sendResponse(ctx, request, response);
        } else if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
            var decoder = new QueryStringDecoder(request.uri());
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/server-info")) {
                handleGetServerInfo(response);
            } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/reverse")) {
                handleReverse(response);
            } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/play")) {
                handlePlay(request);
            } else if (HttpMethod.PUT.equals(request.method()) && decoder.path().equals("/setProperty")) {
                handleSetProperty(request, decoder);
            } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/rate")) {
                handleRate(decoder);
            } else if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/playback-info")) {
                handlePlaybackInfo(response);
            } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/action")) {
                handleAction(request);
            } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/getProperty")) {
                handleGetProperty(decoder);
            } else {
                log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
            }
            sendResponse(ctx, request, response);
        } else {
            log.error("Unknown control request protocol: {}", request.protocolVersion());
        }
    }

    /**
     * Resolves session by the request headers:<br/>
     * {@code Active-Remote} for RTSP<br/>
     * {@code X-Apple-Session-ID} for HTTP
     *
     * @param request incoming request
     * @return active session
     */
    private Session resolveSession(FullHttpRequest request) {
        var sessionId = Optional.ofNullable(request.headers().get("Active-Remote"))
                .orElseGet(() -> request.headers().get("X-Apple-Session-ID"));
        return sessionManager.getSession(sessionId);
    }

    private void handleGetInfo(FullHttpResponse response) throws Exception {
        var info = PropertyListUtil.prepareInfoResponse(airPlayConfig);
        response.content().writeBytes(info);
    }

    private void handlePairSetup(Session session, FullHttpResponse response) throws Exception {
        session.getAirPlay().pairSetup(new ByteBufOutputStream(response.content()));
    }

    private void handlePairVerify(Session session, FullHttpRequest request, FullHttpResponse response) throws Exception {
        session.getAirPlay().pairVerify(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
    }

    private void handleFairPlaySetup(Session session, FullHttpRequest request, FullHttpResponse response) throws Exception {
        session.getAirPlay().fairPlaySetup(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
    }

    private void handleRtspSetup(ChannelHandlerContext ctx, Session session, FullHttpRequest request, FullHttpResponse response) throws Exception {
        var mediaStreamInfo = session.getAirPlay().rtspSetup(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO -> {
                    airPlayConsumer.onAudioFormat((AudioStreamInfo) mediaStreamInfo.get());
                    session.getAudioServer().start(airPlayConsumer);
                    session.getAudioControlServer().start();
                    var setup = PropertyListUtil.prepareSetupAudioResponse(session.getAudioServer().getPort(),
                            session.getAudioControlServer().getPort());
                    response.content().writeBytes(setup);
                }
                case VIDEO -> {
                    airPlayConsumer.onVideoFormat((VideoStreamInfo) mediaStreamInfo.get());
                    session.getVideoServer().start(airPlayConsumer);
                    var setup = PropertyListUtil.prepareSetupVideoResponse(session.getVideoServer().getPort(),
                            ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort(), 0);
                    response.content().writeBytes(setup);
                }
            }
        }
    }

    private void handleRtspGetParameter(FullHttpResponse response) {
        // TODO get requested param and respond accordingly
        byte[] content = "volume: 0.000000\r\n".getBytes(StandardCharsets.US_ASCII);
        response.content().writeBytes(content);
    }

    private void handleRtspRecord(FullHttpResponse response) {
        response.headers().add("Audio-Latency", "11025");
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
    }

    private void handleRtspSetParameter(FullHttpResponse response) {
        // TODO get requested param and respond accordingly
        response.headers().add("Apple-Jack-Status", "connected; type=analog");
    }

    private void handleRtspTeardown(Session session, FullHttpRequest request) throws Exception {
        var mediaStreamInfo = session.getAirPlay().rtspTeardown(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO -> {
                    airPlayConsumer.onAudioSrcDisconnect();
                    session.getAudioServer().stop();
                    session.getAudioControlServer().stop();
                }
                case VIDEO -> {
                    airPlayConsumer.onVideoSrcDisconnect();
                    session.getVideoServer().stop();
                }
            }
        } else {
            airPlayConsumer.onAudioSrcDisconnect();
            airPlayConsumer.onVideoSrcDisconnect();
            session.getAudioServer().stop();
            session.getAudioControlServer().stop();
            session.getVideoServer().stop();
        }
    }

    private void handleGetServerInfo(FullHttpResponse response) {
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        var serverInfo = PropertyListUtil.prepareServerInfoResponse();
        response.content().writeBytes(serverInfo);
    }

    private void handleReverse(FullHttpResponse response) {
        response.setStatus(HttpResponseStatus.SWITCHING_PROTOCOLS);
        response.headers().add(HttpHeaderNames.UPGRADE, "PTTH/1.0");
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        // TODO Get X-Apple-Purpose
    }

    private void handlePlay(FullHttpRequest request) throws Exception {
        NSDictionary play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", play.toXMLPropertyList());
    }

    private void handleSetProperty(FullHttpRequest request, QueryStringDecoder decoder) throws Exception {
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        NSDictionary play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", play.toXMLPropertyList());
    }

    private void handleRate(QueryStringDecoder decoder) {
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
    }

    private void handlePlaybackInfo(FullHttpResponse response) {
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        var playbackInfo = PropertyListUtil.preparePlaybackInfoResponse();
        response.content().writeBytes(playbackInfo);
    }

    private void handleAction(FullHttpRequest request) throws Exception {
        NSDictionary action = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", action.toXMLPropertyList());
    }

    private void handleGetProperty(QueryStringDecoder decoder) {
        // TODO get requested param and respond accordingly
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
    }

    private DefaultFullHttpResponse createRtspResponse(FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        response.headers().clear();

        var cSeq = request.headers().get(RtspHeaderNames.CSEQ);
        if (cSeq != null) {
            response.headers().add(RtspHeaderNames.CSEQ, cSeq);
            response.headers().add(RtspHeaderNames.SERVER, "AirTunes/220.68");
        }

        return response;
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
