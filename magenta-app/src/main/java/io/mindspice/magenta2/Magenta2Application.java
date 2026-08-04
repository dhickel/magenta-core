package io.mindspice.magenta2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Magenta2Application {

    public static void main(String[] args) {
        SpringApplication.run(Magenta2Application.class, args);
    }
}
