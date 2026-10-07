package org.fzb;

import org.fzb.smolagents.cli.SmolagentsCli;

/**
 * Java 重实现项目的命令行启动入口。
 */
public class Main {
    /**
     * 创建项目命令行启动入口。
     */
    public Main() {
    }

    /**
     * 执行 smolagents 命令行并以对应状态码退出。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = new SmolagentsCli().run(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}