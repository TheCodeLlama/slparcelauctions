package com.slparcelauctions.backend.admin;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapInitializerTest {

    @Mock UserRepository userRepository;

    private AdminBootstrapInitializer subject(List<String> emails) {
        AdminBootstrapProperties props = new AdminBootstrapProperties();
        props.setBootstrapUsernames(emails);
        return new AdminBootstrapInitializer(userRepository, props);
    }

    @Test
    void promoteBootstrapAdmins_emptyList_skipsRepoCallEntirely() {
        AdminBootstrapInitializer init = subject(List.of());
        init.promoteBootstrapAdmins();
        verifyNoInteractions(userRepository);
    }

    @Test
    void promoteBootstrapAdmins_callsRepoWithExactList() {
        List<String> emails = List.of("a@x.com", "b@x.com");
        when(userRepository.bulkPromoteByUsernameIfUser(emails)).thenReturn(2);
        AdminBootstrapInitializer init = subject(emails);
        init.promoteBootstrapAdmins();
        verify(userRepository).bulkPromoteByUsernameIfUser(emails);
    }

    @Test
    void promoteBootstrapAdmins_zeroPromotedIsNotAnError() {
        List<String> emails = List.of("absent@x.com");
        when(userRepository.bulkPromoteByUsernameIfUser(emails)).thenReturn(0);
        AdminBootstrapInitializer init = subject(emails);
        init.promoteBootstrapAdmins();
        verify(userRepository).bulkPromoteByUsernameIfUser(emails);
    }
}
