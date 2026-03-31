#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <pty.h>

// Create a PTY and fork a child process
JNIEXPORT jintArray JNICALL
Java_com_clauderemote_platform_PtyProcess_nativeCreatePty(
    JNIEnv *env, jobject thiz,
    jstring cmd, jobjectArray envVars,
    jint rows, jint cols) {

    int master;
    pid_t pid;

    const char *command = (*env)->GetStringUTFChars(env, cmd, NULL);

    pid = forkpty(&master, NULL, NULL, NULL);

    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, cmd, command);
        return NULL;
    }

    if (pid == 0) {
        // Child process
        // Set environment variables
        int envCount = (*env)->GetArrayLength(env, envVars);
        for (int i = 0; i < envCount; i++) {
            jstring envVar = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            const char *envStr = (*env)->GetStringUTFChars(env, envVar, NULL);
            putenv(strdup(envStr));
            (*env)->ReleaseStringUTFChars(env, envVar, envStr);
        }

        // Set terminal size
        struct winsize ws;
        ws.ws_row = rows;
        ws.ws_col = cols;
        ws.ws_xpixel = 0;
        ws.ws_ypixel = 0;
        ioctl(STDIN_FILENO, TIOCSWINSZ, &ws);

        execl("/system/bin/sh", "sh", "-c", command, NULL);
        _exit(1);
    }

    (*env)->ReleaseStringUTFChars(env, cmd, command);

    // Return [masterFd, pid]
    jintArray result = (*env)->NewIntArray(env, 2);
    jint values[2] = { master, pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

// Resize the PTY
JNIEXPORT void JNICALL
Java_com_clauderemote_platform_PtyProcess_nativeResize(
    JNIEnv *env, jobject thiz,
    jint fd, jint rows, jint cols) {

    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}
