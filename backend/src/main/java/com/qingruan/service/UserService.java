package com.qingruan.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qingruan.dto.*;
import com.qingruan.entity.SysUser;
import com.qingruan.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service @RequiredArgsConstructor
public class UserService {
 private final SysUserMapper mapper; private final PasswordEncoder encoder; private final JwtService jwt;
 public record LoginResult(String token,long expiresIn,UserInfo userInfo){}
 public LoginResult login(LoginRequest r){ SysUser u=mapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername,r.username())); if(u==null||u.getStatus()==null||u.getStatus()!=1||!encoder.matches(r.password(),u.getPassword())) throw new BadCredentialsException("用户名或密码错误"); u.setLastLoginTime(LocalDateTime.now()); mapper.updateById(u); return new LoginResult(jwt.create(u.getId(),u.getUsername(),u.getRole()),jwt.expirationSeconds(),UserInfo.from(u)); }
 public List<UserInfo> list(){ return mapper.selectList(null).stream().map(UserInfo::from).toList(); }
 public UserInfo create(UserCreateRequest r){ if(mapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername,r.username()))>0) throw new IllegalArgumentException("用户名已存在"); SysUser u=new SysUser(); u.setUsername(r.username()); u.setPassword(encoder.encode(r.password())); u.setNickname(r.nickname()); u.setRole(r.role()); u.setEmail(r.email()); u.setPhone(r.phone()); u.setStatus(1); mapper.insert(u); return UserInfo.from(u); }
 public UserInfo update(Long id,UserUpdateRequest r){ SysUser u=require(id); if(r.nickname()!=null)u.setNickname(r.nickname()); if(r.role()!=null)u.setRole(r.role()); if(r.status()!=null)u.setStatus(r.status()); if(r.password()!=null&&!r.password().isBlank())u.setPassword(encoder.encode(r.password())); if(r.email()!=null)u.setEmail(r.email()); if(r.phone()!=null)u.setPhone(r.phone()); mapper.updateById(u); return UserInfo.from(u); }
 public void delete(Long id){ if(mapper.deleteById(id)==0) throw new IllegalStateException("用户不存在"); }
 private SysUser require(Long id){ SysUser u=mapper.selectById(id); if(u==null) throw new IllegalStateException("用户不存在"); return u; }
}
