package org.example.xqy1._026_silver_residence.pojo;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 用户登录角色
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "User" )
public class User {
    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    private String password;

    @Indexed(unique = true, sparse = true)
    private String email;

    private String phone;

    private String address;

    private Date createTime;

    private Date updateTime;

}
