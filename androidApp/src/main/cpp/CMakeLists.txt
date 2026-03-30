cmake_minimum_required(VERSION 3.22.1)
project("claude-remote")

add_library(pty_helper SHARED pty_helper.c)
target_link_libraries(pty_helper log)
