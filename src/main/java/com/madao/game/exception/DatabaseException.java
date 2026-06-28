package com.madao.game.exception;

/**
 * 数据库持久层异常 —— 替代 DAO 层中直接抛出的 RuntimeException。
 *
 * <p>用于包装所有数据库操作相关的异常（如 {@link java.sql.SQLException}），
 * 使调用方可以通过捕捉 {@code DatabaseException} 精确区分持久层异常与业务层异常，
 * 便于统一处理数据访问错误。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * catch (SQLException e) {
 *     throw new DatabaseException("插入玩家数据失败", e);
 * }
 * }</pre>
 *
 * @author madao
 */
public class DatabaseException extends RuntimeException {

    /**
     * 仅携带错误描述消息的异常。
     * @param message 错误描述
     */
    public DatabaseException(String message) {
        super(message);
    }

    /**
     * 携带错误描述和原始原因的异常。
     * @param message 错误描述
     * @param cause   原始异常（如 SQLException）
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
