@asynccontextmanager
async def streamable_http_client(
    url: str,
    *,
    http_client: httpx2.AsyncClient | None = None,
    terminate_on_close: bool = True,
    max_sse_event_size: int | None = DEFAULT_MAX_SSE_EVENT_SIZE,
) -> AsyncGenerator[TransportStreams, None]:
    """Client transport for StreamableHTTP.

    Args:
        url: The MCP server endpoint URL.
        http_client: Optional pre-configured httpx2.AsyncClient. If None, a default
            client with recommended MCP timeouts will be created. To configure headers,
            authentication, or other HTTP settings, create an httpx2.AsyncClient and pass it here.
            Whichever client is used, MCP requests follow a redirect only when it stays on the
            endpoint's origin (same scheme, host and port, or http to https on the same host with
            default ports) and keeps the request method (307/308 for a POST; any status for the GET
            stream); any other redirect is not followed and the message it answered fails with an
            error naming the location. The
            client's `follow_redirects` setting is not consulted; the SDK's OAuth providers apply the
            same rule to the requests they make.
        terminate_on_close: If True, send a DELETE request to terminate the session when the context exits.
        max_sse_event_size: Maximum bytes buffered for one SSE event. None disables the limit.
            JSON responses are not affected.

    Yields:
        Tuple containing:
            - read_stream: Stream for reading messages from the server
            - write_stream: Stream for sending messages to the server

    Example:
        See examples/snippets/clients/ for usage patterns.
    """
    # Determine if we need to create and manage the client
    client_provided = http_client is not None
    client = http_client

    if client is None:
        # Create default client with recommended MCP timeouts
        client = create_mcp_http_client()

    transport = StreamableHTTPTransport(url, max_sse_event_size=max_sse_event_size)

    logger.debug(f"Connecting to StreamableHTTP endpoint: {url}")

    async with contextlib.AsyncExitStack() as stack:
        # Only manage client lifecycle if we created it
        if not client_provided:
            await stack.enter_async_context(client)

        read_stream_writer, read_stream = create_context_streams[SessionMessage | Exception](0)
        write_stream, write_stream_reader = create_context_streams[SessionMessage](0)

        async with (
            read_stream_writer,
            read_stream,
            write_stream,
            write_stream_reader,
            anyio.create_task_group() as tg,
        ):

            def start_get_stream() -> None:
                tg.start_soon(transport.handle_get_stream, client, read_stream_writer)

            tg.start_soon(
                transport.post_writer,
                client,
                write_stream_reader,
                read_stream_writer,
                write_stream,
                start_get_stream,
                tg,
            )

            try:
                yield read_stream, write_stream
            finally:
                if transport.session_id and terminate_on_close:
                    await transport.terminate_session(client)
                tg.cancel_scope.cancel()
        await resync_tracer()