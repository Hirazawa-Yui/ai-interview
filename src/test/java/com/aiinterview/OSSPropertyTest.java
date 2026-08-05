package com.aiinterview;

import com.aiinterview.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class OSSPropertyTest {

    @Autowired
    private StorageProperties storageProperties;

    @Test
    public void propertyTest(){
        System.out.println(storageProperties);
    }
}
