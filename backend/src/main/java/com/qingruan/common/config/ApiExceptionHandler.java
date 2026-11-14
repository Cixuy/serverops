package com.qingruan.common.config;
import org.springframework.dao.DataAccessException; import org.springframework.http.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.bind.annotation.*; import java.util.Map;
import java.util.stream.Collectors;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e){
   // 收集所有字段校验错误，提示具体缺失/不合法的字段及原因，避免笼统返回"请求参数不合法"
   String message = e.getBindingResult().getFieldErrors().stream()
           .map(err -> err.getField() + (err.getDefaultMessage() != null ? " " + err.getDefaultMessage() : "不能为空"))
           .distinct()
           .collect(Collectors.joining("；"));
   return ResponseEntity.badRequest().body(Map.of("code",400,"message",message));
 }
 @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class) ResponseEntity<?> auth(Exception e){return ResponseEntity.status(401).body(Map.of("code",401,"message","用户名或密码错误"));}
 @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<?> denied(Exception e){return ResponseEntity.status(403).body(Map.of("code",403,"message","无权限执行此操作"));}
 @ExceptionHandler({IllegalStateException.class}) ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("code",404,"message",e.getMessage()));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> conflict(Exception e){return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage()));}
 @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(DataAccessException e){
   // 打印完整堆栈便于定位数据库问题，并返回底层真实原因（如未知列、连接异常等）
   e.printStackTrace();
   Throwable cause = e.getMostSpecificCause();
   String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "数据库连接或操作失败";
   return ResponseEntity.status(503).body(Map.of("code",503,"message",message));
 }
}
