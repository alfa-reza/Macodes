#ifndef TERMISH_SFTP_WRITE_H
#define TERMISH_SFTP_WRITE_H

#include <libssh2_sftp.h>

/* Byte version: When writing to libssh2, `const char*` is written as `String` by cinterop,
 * Here, we use `unsigned char*` to have CInterop generate a pointer parameter, thereby preventing binary data from being corrupted by UTF-8. */
static inline long termish_sftp_write(LIBSSH2_SFTP_HANDLE *handle,
                                      const unsigned char *buffer,
                                      size_t count) {
    return (long)libssh2_sftp_write(handle, (const char *)buffer, count);
}

static inline long termish_channel_write(LIBSSH2_CHANNEL *channel,
                                         const unsigned char *buffer,
                                         size_t count) {
    return (long)libssh2_channel_write_ex(channel, 0, (const char *)buffer, count);
}

#endif
