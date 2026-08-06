# Changes
1. Direct-to-Storage Downloads: Bypassed the system DownloadManager for the actual data transfer. Asgard now writes bytes directly to your chosen download folder from the start. This completely eliminates the secondary "move" step that was causing failures on some devices.
2. Parallel Chunked Downloading:
    - Implemented a high-performance multithreaded downloader using OkHttp and Coroutines.
    - For files larger than 5MB, Asgard now opens 3 simultaneous connections to YouTube's servers, downloading different parts of the file in parallel. This typically doubles or triples download speeds, as it bypasses single-connection throttling.
3. Optimized Buffering: Increased the network buffer size to 64KB for smoother data flow and reduced CPU overhead during high-speed transfers.
4. Improved Thread Safety: Used low-level FileChannel positional writes, which allow multiple threads to safely write to the same file at once without data corruption.

# Reliability Fixes:
- Move Error fixed: Since we write directly to the final destination, there is no more moving required.
- Folder Access: Improved SAF (Storage Access Framework) checks to ensure the app handles lost folder permissions gracefully.
- UI Stability: Fixed several edge-case crashes when clearing previews or adding items rapidly.
