package com.aiinterview.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 配置
 * <p>
 * MyBatis-Plus 3.5.x 的自动配置（MybatisPlusAutoConfiguration）在 Spring Boot 4.x 下
 * 因为底层 API 变更无法自动生效，因此手动创建 SqlSessionFactory + SqlSessionTemplate。
 * 这是本项目中唯一需要绕开自动配置的地方，其他模块不受影响。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * SqlSessionFactory — MyBatis 核心工厂
     * <p>
     * 使用 MyBatis-Plus 的 MybatisSqlSessionFactoryBean（继承自 MyBatis 原版），
     * 自动注入 DataSource 和 MybatisPlusInterceptor。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                                MybatisPlusInterceptor interceptor) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(interceptor);
        return factory.getObject();
    }

    /**
     * SqlSessionTemplate — MyBatis 线程安全的 SqlSession 封装
     */
    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * MyBatis-Plus 拦截器（分页、防全表更新等插件通过此 Bean 注入）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        return new MybatisPlusInterceptor();
    }
}
