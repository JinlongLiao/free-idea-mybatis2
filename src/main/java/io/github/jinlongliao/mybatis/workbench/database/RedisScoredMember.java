package io.github.jinlongliao.mybatis.workbench.database;

/**
 * Redis 有序集合预览中的成员文本及其分数。
 *
 * @param member 以 UTF-8 解码的成员文本
 * @param score Redis 返回的分数，允许正负无穷
 * @author liaojinlong
 * @since 2026/11/19 15:32
 */
public record RedisScoredMember(String member, double score) {
}
