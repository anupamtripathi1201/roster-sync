package com.moveinsync.rostersync.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
@Service
public class UserAccountService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(JdbcTemplate jdbc,PasswordEncoder passwordEncoder){
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }
    @Override
    public CurrentUser loadUserByUsername(String username) {
        List<CurrentUser> found = jdbc.query(
                "select username, password_hash, role, tenant_id, employee_id from app_user where username = ?",
                (rs, i) -> new CurrentUser(rs.getString(1), rs.getString(2), CurrentUser.Role.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5)),
                username);
        if (found.isEmpty()) {
            // Spring turns this into the same 401 as a wrong password: no user enumeration
            throw new UsernameNotFoundException("unknown user");
        }
        return found.get(0);
    }


    public void createUser(String username, String rawPassword, CurrentUser.Role role, String tenantId, String employeeId) {
        jdbc.update("insert into app_user (username, password_hash, role, tenant_id, employee_id) values (?, ?, ?, ?, ?)",
                username, passwordEncoder.encode(rawPassword), role.name(), tenantId, employeeId);
    }


}
