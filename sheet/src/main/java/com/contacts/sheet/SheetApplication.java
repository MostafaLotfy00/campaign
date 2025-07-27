package com.contacts.sheet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SheetApplication {

	public static void main(String[] args) {
		SpringApplication.run(SheetApplication.class, args);
	}

}
