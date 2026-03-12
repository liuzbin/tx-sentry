package com.web3.txsentry.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * configuration class for the citus distributed postgresql cluster.
 * it explicitly initializes the datasource and binds it to specific mapper paths.
 */
@Configuration
@MapperScan(
        // strictly scan only the mappers under the citus package.
        basePackages = "com.web3.txsentry.mapper.citus",
        sqlSessionFactoryRef = "citussqlsessionfactory"
)
public class CitusConfig {

    /**
     * initialize the hikari datasource using properties from application.yml.
     * marked as primary since citus is our core transaction database.
     */
    @Primary
    @Bean(name = "citusdatasource")
    @ConfigurationProperties(prefix = "spring.datasource.citus")
    public DataSource citusDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * configure the mybatis sql session factory and map the xml file locations.
     */
    @Primary
    @Bean(name = "citussqlsessionfactory")
    public SqlSessionFactory citusSqlSessionFactory(@Qualifier("citusdatasource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);

        // bind the xml mapper paths. ensure the directory structure is completely lowercase.
        bean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/citus/*.xml")
        );

        // enable camelcase mapping for database columns (e.g., biz_order_id to bizOrderId)
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);

        return bean.getObject();
    }

    /**
     * configure the transaction manager for citus.
     * this ensures @transactional works correctly for this specific datasource.
     */
    @Primary
    @Bean(name = "citustransactionmanager")
    public DataSourceTransactionManager citusTransactionManager(@Qualifier("citusdatasource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}