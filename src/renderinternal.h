#ifndef RENDERINTERNAL_H
#define RENDERINTERNAL_H

#include <vectormath/cpp/vectormath_aos.h>
#include "render/render.h"


namespace dmRender
{
	using namespace Vectormath::Aos;

	struct RenderObject
	{
		Vector4				m_Colour[MAX_COLOR];
		Point3				m_Pos;
		Quat				m_Rot;
		Vector3             m_Size;
		uint64_t            m_Mask;
		uint32_t        	m_Type;
		void*				m_Go;
		void*				m_Data;
		uint32_t            m_UserData[4];
		uint16_t			m_MarkForDelete:1;
		uint16_t            m_Enabled:1;
	};

}

#endif

