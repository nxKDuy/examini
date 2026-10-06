package com.datn.auth.service;

import com.datn.auth.dto.ContactDto;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final UserRepository userRepository;

    /** Một truy vấn batch; giữ thứ tự yêu cầu, bỏ trùng, bỏ qua ID không tồn tại. Không cache/lưu bản sao. */
    @Transactional(readOnly = true)
    public List<ContactDto.Contact> contacts(List<UUID> userIds) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(userIds);
        Map<UUID, User> found = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(user -> new ContactDto.Contact(user.getId(), user.getEmail(), user.getFullName()))
                .toList();
    }
}
