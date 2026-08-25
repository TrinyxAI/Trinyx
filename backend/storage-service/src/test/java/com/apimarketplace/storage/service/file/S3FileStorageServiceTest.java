package com.apimarketplace.storage.service.file;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3FileStorageServiceTest {

    @Test
    void successfulDeleteIsTerminalEvenWhenTheObjectWasAlreadyAbsent() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        S3FileStorageService service = service(client);

        assertThat(service.delete("tenant-1/missing.pdf")).isTrue();
        verify(client).deleteObject(argThat((DeleteObjectRequest request) ->
                "workflow-files".equals(request.bucket())
                        && "tenant-1/missing.pdf".equals(request.key())));
    }

    @Test
    void explicitNoSuchKeyIsAlsoAnIdempotentSuccess() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder()
                        .message("already absent")
                        .build());
        S3FileStorageService service = service(client);

        assertThat(service.delete("tenant-1/missing.pdf")).isTrue();
    }

    private static S3FileStorageService service(S3Client client) throws Exception {
        S3FileStorageService service = new S3FileStorageService();
        set(service, "s3Client", client);
        set(service, "bucket", "workflow-files");
        return service;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
