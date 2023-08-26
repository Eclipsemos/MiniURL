package cn.itmtx.ddd.ezlink.domain.transform.assembler;

import cn.itmtx.ddd.ezlink.client.dto.data.UrlMapDTO;
import cn.itmtx.ddd.ezlink.domain.transform.CompressionCodeDO;
import cn.itmtx.ddd.ezlink.domain.transform.UrlMapDO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/**
 * DO <-> DTO
 */
@Component
public class UrlMapDOAssembler {
    public UrlMapDO fromUrlMapDTO(UrlMapDTO urlMapDTO) {
        UrlMapDO urlMapDO = new UrlMapDO();
        BeanUtils.copyProperties(urlMapDTO, urlMapDO);
        return urlMapDO;
    }

    public UrlMapDTO toUrlMapDTO(UrlMapDO urlMapDO) {
        UrlMapDTO urlMapDTO = new UrlMapDTO();
        BeanUtils.copyProperties(urlMapDO, urlMapDTO);
        return urlMapDTO;
    }
}
