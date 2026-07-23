package com.nectrix.coreapp.auth.api;

import com.nectrix.coreapp.auth.domain.User;
import com.nectrix.coreapp.auth.repository.UserRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserAdminApiImpl implements UserAdminApi {

  private final UserRepository userRepository;

  public UserAdminApiImpl(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public List<UserView> search(String query, String status, int page, int pageSize) {
    return userRepository.search(query, status, page, pageSize).stream().map(this::toView).toList();
  }

  @Override
  public UserView getUser(UUID id) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("No such user: " + id));
    return toView(user);
  }

  @Override
  public Optional<UserView> findByEmail(String email) {
    return userRepository.findByEmail(email).map(this::toView);
  }

  @Override
  public void updateStatus(UUID id, String status) {
    userRepository.updateStatus(id, status);
  }

  private UserView toView(User user) {
    return new UserView(
        user.id(),
        user.email(),
        user.displayName(),
        user.twoFactorEnabled(),
        user.status(),
        user.createdAt());
  }
}
