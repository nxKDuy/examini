package com.datn.auth.service;

import com.datn.auth.dto.IdentityDto;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IdentityResolutionService {

    private final UserRepository userRepository;

    /** Một truy vấn batch; theo thứ tự yêu cầu (đã bỏ trùng), bỏ qua selector không tồn tại. Email so khớp chính xác. */
    @Transactional(readOnly = true)
    public List<IdentityDto.Identity> resolve(IdentityDto.ResolveRequest req) {
        if (req.userIds() != null) {
            LinkedHashSet<UUID> ids = new LinkedHashSet<>(req.userIds());
            Map<UUID, User> found = userRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(User::getId, Function.identity()));
            return ordered(ids, found);
        }
        LinkedHashSet<String> emails = new LinkedHashSet<>(req.emails());
        Map<String, User> found = userRepository.findByEmailIn(emails).stream()
                .collect(Collectors.toMap(User::getEmail, Function.identity()));
        return ordered(emails, found);
    }

    private <K> List<IdentityDto.Identity> ordered(Iterable<K> keys, Map<K, User> found) {
        List<IdentityDto.Identity> out = new java.util.ArrayList<>();
        for (K key : keys) {
            User u = found.get(key);
            if (u != null) out.add(new IdentityDto.Identity(u.getId(), u.getFullName(), u.getEmail(),
                    u.getPersonalPersona() == null ? null : u.getPersonalPersona().name()));
        }
        return out;
    }
}
