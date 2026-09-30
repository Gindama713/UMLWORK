package cn.jsuhao.parking.pass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class PassServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PassServiceApplication.class, args);
    }
}
