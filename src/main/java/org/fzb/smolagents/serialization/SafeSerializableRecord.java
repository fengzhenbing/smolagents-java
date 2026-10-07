package org.fzb.smolagents.serialization;

/**
 * 显式允许一个无副作用 Java record 被 {@link SafeSerializer} 遍历。
 *
 * <p>实现此接口表示同意序列化器调用该 record 的组件 accessor。仅应由 accessor 无副作用、
 * 且组件本身可安全序列化的 record 实现此接口。</p>
 */
public interface SafeSerializableRecord {
}
